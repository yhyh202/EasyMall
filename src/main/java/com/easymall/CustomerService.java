package com.easymall;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CustomerService {
    private final Db db;
    public CustomerService(Db db) { this.db=db; }
    private void lockUser(long user) { db.one("SELECT id FROM mall_user WHERE id=? FOR UPDATE",user); }
    public List<Map<String,Object>> addresses(long user) { return db.rows("SELECT * FROM address WHERE user_id=? ORDER BY is_default DESC,id DESC",user); }
    @Transactional public Map<String,Object> saveAddress(long user,Long id,Requests.Address a) {
        lockUser(user);
        if(id!=null) db.one("SELECT id FROM address WHERE id=? AND user_id=?",id,user);
        if(a.isDefault()) db.update("UPDATE address SET is_default=FALSE WHERE user_id=?",user);
        if(id==null) id=db.insert("INSERT INTO address(user_id,recipient,phone,detail,is_default) VALUES (?,?,?,?,?)",user,a.recipient(),a.phone(),a.detail(),a.isDefault());
        else db.update("UPDATE address SET recipient=?,phone=?,detail=?,is_default=? WHERE id=?",a.recipient(),a.phone(),a.detail(),a.isDefault(),id);
        return db.one("SELECT * FROM address WHERE id=?",id);
    }
    @Transactional public void deleteAddress(long user,long id) { lockUser(user); if(db.update("DELETE FROM address WHERE id=? AND user_id=?",id,user)==0) throw BusinessException.missing(); }
    public List<Map<String,Object>> cart(long user) {
        return db.rows("SELECT c.id,c.product_id,c.quantity,p.name,p.price,p.stock,p.active FROM cart_item c JOIN product p ON c.product_id=p.id WHERE c.user_id=? ORDER BY c.id",user);
    }
    @Transactional public List<Map<String,Object>> putCart(long user,Requests.Cart c) {
        lockUser(user);
        db.one("SELECT id FROM product WHERE id=? AND active=TRUE",c.productId());
        if(db.update("UPDATE cart_item SET quantity=? WHERE user_id=? AND product_id=?",c.quantity(),user,c.productId())==0)
            db.update("INSERT INTO cart_item(user_id,product_id,quantity) VALUES (?,?,?)",user,c.productId(),c.quantity());
        return cart(user);
    }
    @Transactional public List<Map<String,Object>> quantity(long user,long id,int quantity) {
        lockUser(user); if(db.update("UPDATE cart_item SET quantity=? WHERE id=? AND user_id=?",quantity,id,user)==0) throw BusinessException.missing(); return cart(user);
    }
    @Transactional public void deleteCart(long user,Long id) {
        lockUser(user);
        if(id==null) db.update("DELETE FROM cart_item WHERE user_id=?",user);
        else if(db.update("DELETE FROM cart_item WHERE id=? AND user_id=?",id,user)==0) throw BusinessException.missing();
    }
}
