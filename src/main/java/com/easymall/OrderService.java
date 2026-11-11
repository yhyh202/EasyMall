package com.easymall;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class OrderService {
    private final Db db;
    public OrderService(Db db) { this.db=db; }
    public static void validateKey(String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{8,64}")) throw new BusinessException(400,"VALIDATION_ERROR","Idempotency-Key 必须为 8..64 位字母数字或 ._:-");
    }
    private void lockUser(long user) { db.one("SELECT id FROM mall_user WHERE id=? FOR UPDATE",user); }
    private Map<String,Object> owned(long user,long id,boolean lock) { return db.one("SELECT * FROM mall_order WHERE id=? AND user_id=?"+(lock?" FOR UPDATE":""),id,user); }
    @Transactional public Map<String,Object> create(long user,Requests.Order request,String key) {
        validateKey(key);
        // Serialize writes for one user; different buyers still compete at product rows.
        lockUser(user);
        var lines=new TreeMap<Long,Integer>();
        for(var line:request.items()) if(lines.putIfAbsent(line.productId(),line.quantity())!=null) throw new BusinessException(400,"VALIDATION_ERROR","同一商品不能出现多次");
        String hash=AuthService.hash(request.addressId()+"|"+request.clearCart()+"|"+lines);
        var previous=db.rows("SELECT * FROM mall_order WHERE user_id=? AND request_key=?",user,key);
        if(!previous.isEmpty()) {
            var order=previous.getFirst();
            if(!hash.equals(order.get("request_hash"))) throw BusinessException.conflict("幂等键已用于不同下单内容");
            return detail(user,Db.number(order,"id"),false);
        }
        var address=db.one("SELECT * FROM address WHERE id=? AND user_id=?",request.addressId(),user);
        var products=new LinkedHashMap<Long,Map<String,Object>>(); BigDecimal total=BigDecimal.ZERO;
        // Always lock products in ascending ID order to reduce cross-order deadlocks.
        for(var line:lines.entrySet()) {
            var product=db.one("SELECT * FROM product WHERE id=? FOR UPDATE",line.getKey());
            if(!Db.bool(product,"active")) throw BusinessException.conflict("商品已下架");
            if(Db.number(product,"stock")<line.getValue()) throw BusinessException.conflict("库存不足");
            products.put(line.getKey(),product);
            total=total.add(((BigDecimal)product.get("price")).multiply(BigDecimal.valueOf(line.getValue())));
        }
        if(total.compareTo(new BigDecimal("999999999999.99"))>0) throw new BusinessException(400,"VALIDATION_ERROR","订单金额超过上限");
        long order=db.insert("INSERT INTO mall_order(user_id,request_key,request_hash,status,total_amount,recipient,phone,address_detail) VALUES (?,?,?,'PENDING',?,?,?,?)",user,key,hash,total,address.get("recipient"),address.get("phone"),address.get("detail"));
        for(var line:lines.entrySet()) {
            long product=line.getKey(); int quantity=line.getValue(); var snapshot=products.get(product);
            if(db.update("UPDATE product SET stock=stock-? WHERE id=? AND stock>=?",quantity,product,quantity)!=1) throw BusinessException.conflict("库存不足");
            db.update("INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity) VALUES (?,?,?,?,?)",order,product,snapshot.get("name"),snapshot.get("price"),quantity);
            db.update("INSERT INTO stock_log(product_id,order_id,actor_id,delta,reason) VALUES (?,?,?,?,'RESERVE')",product,order,user,-quantity);
            if(request.clearCart()) {
                // Remove only purchased quantities, retaining any extra quantities in the cart.
                db.update("DELETE FROM cart_item WHERE user_id=? AND product_id=? AND quantity<=?",user,product,quantity);
                db.update("UPDATE cart_item SET quantity=quantity-? WHERE user_id=? AND product_id=?",quantity,user,product);
            }
        }
        return detail(user,order,false);
    }
    public Api.Page<Map<String,Object>> list(long user,boolean admin,String status,int page,int size) {
        var args=new ArrayList<Object>(); String where=" WHERE 1=1";
        if(!admin) { where+=" AND user_id=?"; args.add(user); }
        if(status!=null) { if(!Set.of("PENDING","PAID","SHIPPED","COMPLETED","CANCELLED").contains(status)) throw new BusinessException(400,"VALIDATION_ERROR","订单状态无效"); where+=" AND status=?"; args.add(status); }
        var result=db.page("mall_order",where,args,page,size);
        result.items().forEach(row->row.remove("request_hash"));
        return result;
    }
    public Map<String,Object> detail(long user,long id,boolean admin) {
        var order=admin?db.one("SELECT * FROM mall_order WHERE id=?",id):owned(user,id,false);
        order.remove("request_hash");
        order.put("items",db.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY product_id",id));
        return order;
    }
    @Transactional public Map<String,Object> cancel(long user,long id) {
        lockUser(user); var order=owned(user,id,true);
        if("CANCELLED".equals(order.get("status"))) return detail(user,id,false);
        if(!"PENDING".equals(order.get("status"))) throw BusinessException.conflict("只有未支付订单可以取消");
        for(var item:db.rows("SELECT * FROM order_item WHERE order_id=? ORDER BY product_id",id)) {
            long product=Db.number(item,"product_id"),quantity=Db.number(item,"quantity");
            db.update("UPDATE product SET stock=stock+? WHERE id=?",quantity,product);
            db.update("INSERT INTO stock_log(product_id,order_id,actor_id,delta,reason) VALUES (?,?,?,?,'RELEASE')",product,id,user,quantity);
        }
        db.update("UPDATE mall_order SET status='CANCELLED' WHERE id=?",id);
        return detail(user,id,false);
    }
    @Transactional public Map<String,Object> pay(long user,long orderId,String key) {
        validateKey(key); lockUser(user); var order=owned(user,orderId,true);
        var previous=db.rows("SELECT * FROM payment WHERE user_id=? AND request_key=?",user,key);
        if(!previous.isEmpty()) {
            if(Db.number(previous.getFirst(),"order_id")!=orderId) throw BusinessException.conflict("支付幂等键已用于其他订单");
            return previous.getFirst();
        }
        if(!"PENDING".equals(order.get("status"))) throw BusinessException.conflict("订单不是待支付状态；支付重试请沿用原幂等键");
        long payment=db.insert("INSERT INTO payment(order_id,user_id,request_key,amount,status) VALUES (?,?,?,?,'SUCCESS')",orderId,user,key,order.get("total_amount"));
        db.update("UPDATE mall_order SET status='PAID',paid_at=CURRENT_TIMESTAMP WHERE id=?",orderId);
        return db.one("SELECT * FROM payment WHERE id=?",payment);
    }
    public Map<String,Object> payment(long user,long order) { owned(user,order,false); return db.one("SELECT * FROM payment WHERE order_id=?",order); }
    @Transactional public Map<String,Object> ship(long orderId,String tracking) {
        var order=db.one("SELECT * FROM mall_order WHERE id=? FOR UPDATE",orderId);
        if("SHIPPED".equals(order.get("status")) && tracking.equals(order.get("shipping_no"))) return detail(0,orderId,true);
        if(!"PAID".equals(order.get("status"))) throw BusinessException.conflict("只有已支付订单可以发货");
        db.update("UPDATE mall_order SET status='SHIPPED',shipping_no=? WHERE id=?",tracking,orderId);
        return detail(0,orderId,true);
    }
    @Transactional public Map<String,Object> receive(long user,long orderId) {
        lockUser(user); var order=owned(user,orderId,true);
        if("COMPLETED".equals(order.get("status"))) return detail(user,orderId,false);
        if(!"SHIPPED".equals(order.get("status"))) throw BusinessException.conflict("只有已发货订单可以确认收货");
        db.update("UPDATE mall_order SET status='COMPLETED' WHERE id=?",orderId);
        return detail(user,orderId,false);
    }
}
