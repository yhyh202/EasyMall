package com.easymall;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    private final Db db;
    private final PasswordEncoder passwords;
    private final int hours;
    private final String dummyHash;
    private final SecureRandom random=new SecureRandom();
    private final RequestSignature signature;
    public AuthService(Db db,PasswordEncoder passwords,RequestSignature signature,@Value("${app.token-hours}") int hours) {
        this.db=db; this.passwords=passwords; this.signature=signature; this.hours=hours; this.dummyHash=passwords.encode("dummy-login-timing-password");
    }
    @Transactional public Map<String,Object> register(Requests.Credentials c) {
        long id=db.insert("INSERT INTO mall_user(username,password_hash,role) VALUES (?,?,'USER')",c.username().toLowerCase(Locale.ROOT),passwords.encode(c.password()));
        return db.one("SELECT id,username,role,created_at FROM mall_user WHERE id=?",id);
    }
    @Transactional public Map<String,Object> login(Requests.Credentials c) {
        var rows=db.rows("SELECT * FROM mall_user WHERE username=?",c.username().toLowerCase(Locale.ROOT));
        boolean valid=passwords.matches(c.password(),rows.isEmpty()?dummyHash:(String)rows.getFirst().get("password_hash"));
        if(rows.isEmpty() || !valid) throw new BusinessException(401,"INVALID_CREDENTIALS","用户名或密码错误");
        byte[] bytes=new byte[32]; random.nextBytes(bytes); String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiry=Instant.now().plusSeconds(hours*3600L);
        db.update("DELETE FROM auth_token WHERE user_id=? AND expires_at<=CURRENT_TIMESTAMP",Db.number(rows.getFirst(),"id"));
        String tokenHash=hash(token);
        db.update("INSERT INTO auth_token(token_hash,user_id,expires_at) VALUES (?,?,?)",tokenHash,Db.number(rows.getFirst(),"id"),Timestamp.from(expiry));
        // signKey is derived from the token, not stored; it is returned only here.
        return Map.of("token",token,"tokenType","Bearer","signKey",signature.signKey(tokenHash),"expiresAt",expiry,"user",Map.of("id",rows.getFirst().get("id"),"username",rows.getFirst().get("username"),"role",rows.getFirst().get("role")));
    }
    public void logout(String header) { db.update("DELETE FROM auth_token WHERE token_hash=?",hash(header.substring(7))); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
