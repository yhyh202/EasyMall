package com.easymall;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CatalogService {
    private final Db db;
    public CatalogService(Db db) { this.db=db; }
    public Api.Page<Map<String,Object>> products(String keyword,Long categoryId,boolean admin,int page,int size) {
        String where=admin?" WHERE 1=1":" WHERE active=TRUE"; var args=new ArrayList<Object>();
        if(keyword!=null && !keyword.isBlank()) { if(keyword.length()>100) throw new BusinessException(400,"VALIDATION_ERROR","keyword 过长"); where+=" AND name LIKE ?"; args.add("%"+keyword+"%"); }
        if(categoryId!=null) { where+=" AND category_id=?"; args.add(categoryId); }
        return db.page("product",where,args,page,size);
    }
    public Map<String,Object> product(long id,boolean admin) { return db.one("SELECT * FROM product WHERE id=?"+(admin?"":" AND active=TRUE"),id); }
    public List<Map<String,Object>> categories() { return db.rows("SELECT * FROM category ORDER BY id"); }
    public Map<String,Object> saveCategory(Long id,Requests.Category c) {
        if(id==null) id=db.insert("INSERT INTO category(name) VALUES (?)",c.name());
        else if(db.update("UPDATE category SET name=? WHERE id=?",c.name(),id)==0) throw BusinessException.missing();
        return db.one("SELECT * FROM category WHERE id=?",id);
    }
    public void deleteCategory(long id) { if(db.update("DELETE FROM category WHERE id=?",id)==0) throw BusinessException.missing(); }
    @Transactional public Map<String,Object> saveProduct(Long id,Requests.Product p) {
        db.one("SELECT id FROM category WHERE id=?",p.categoryId());
        if(id==null) id=db.insert("INSERT INTO product(category_id,name,description,price,active,stock) VALUES (?,?,?,?,?,0)",p.categoryId(),p.name(),p.description(),p.price(),p.active());
        else if(db.update("UPDATE product SET category_id=?,name=?,description=?,price=?,active=? WHERE id=?",p.categoryId(),p.name(),p.description(),p.price(),p.active(),id)==0) throw BusinessException.missing();
        return product(id,true);
    }
    public void deactivate(long id) { if(db.update("UPDATE product SET active=FALSE WHERE id=?",id)==0) throw BusinessException.missing(); }
    @Transactional public Map<String,Object> stock(long actor,long product,Requests.Stock s,String key) {
        OrderService.validateKey(key);
        db.one("SELECT id FROM mall_user WHERE id=? FOR UPDATE",actor);
        var existing=db.rows("SELECT * FROM stock_log WHERE actor_id=? AND request_key=?",actor,key);
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(Db.number(row,"product_id")!=product || Db.number(row,"delta")!=s.delta()) throw BusinessException.conflict("幂等键已用于其他库存请求");
            return row;
        }
        if(s.delta()==0) throw new BusinessException(400,"VALIDATION_ERROR","delta 不能为 0");
        db.one("SELECT id FROM product WHERE id=? FOR UPDATE",product);
        if(db.update("UPDATE product SET stock=stock+? WHERE id=? AND stock+? BETWEEN 0 AND 1000000000",s.delta(),product,s.delta())!=1) throw BusinessException.conflict("库存不足或超过上限");
        long id=db.insert("INSERT INTO stock_log(product_id,actor_id,delta,reason,request_key) VALUES (?,?,?,'ADJUST',?)",product,actor,s.delta(),key);
        return db.one("SELECT * FROM stock_log WHERE id=?",id);
    }
    public Api.Page<Map<String,Object>> stockLogs(long product,int page,int size) { return db.page("stock_log"," WHERE product_id=?",List.of(product),page,size); }
}
