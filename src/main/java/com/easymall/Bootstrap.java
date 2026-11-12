package com.easymall;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Bootstrap implements CommandLineRunner {
    private final Db db;
    private final PasswordEncoder passwords;
    private final String username,password;
    private final boolean seed;
    private final SearchCountCache counts;
    public Bootstrap(Db db,PasswordEncoder passwords,SearchCountCache counts,@Value("${app.admin-username}") String username,@Value("${app.admin-password}") String password,@Value("${app.seed-data}") boolean seed) {
        this.db=db; this.passwords=passwords; this.counts=counts; this.username=username; this.password=password; this.seed=seed;
    }
    @Override @Transactional public void run(String... args) {
        if(!password.isBlank() && db.rows("SELECT id FROM mall_user WHERE username=?",username.toLowerCase(java.util.Locale.ROOT)).isEmpty()) {
            if(!username.matches("[a-zA-Z0-9_]{3,50}") || !password.matches("[\\x21-\\x7E]{12,64}")) throw new IllegalArgumentException("管理员用户名格式不合法或密码不是 12..64 位可打印 ASCII 字符（不含空格）");
            db.update("INSERT INTO mall_user(username,password_hash,role) VALUES (?,?,'ADMIN')",username.toLowerCase(java.util.Locale.ROOT),passwords.encode(password));
        }
        if(seed && db.rows("SELECT id FROM category").isEmpty()) {
            long category=db.insert("INSERT INTO category(name) VALUES ('演示商品')");
            long actor=Db.number(db.one("SELECT id FROM mall_user WHERE role='ADMIN' ORDER BY id LIMIT 1"),"id");
            for(int i=1;i<=20;i++) {
                long product=db.insert("INSERT INTO product(category_id,name,description,price,stock,active) VALUES (?,?,?,?,10000,TRUE)",category,"演示商品 "+i,"本地开发与压测样例",new java.math.BigDecimal("19.90").add(java.math.BigDecimal.valueOf(i)));
                db.update("INSERT INTO stock_log(product_id,actor_id,delta,reason) VALUES (?,?,10000,'SEED')",product,actor);
            }
            // Redis may outlive a recreated database; drop totals cached against the old data.
            counts.invalidateAfterCommit();
        }
    }
}
