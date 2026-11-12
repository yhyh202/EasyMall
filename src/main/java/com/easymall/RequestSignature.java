package com.easymall;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Request signing for every request that carries a Bearer token.
 *
 * Login returns a signKey derived from the token: signKey = Base64url(HMAC-SHA256(masterKey, "easymall-sign-key:v1:" + SHA256(token))).
 * The server never stores it; it recomputes it from the token on each request. The client signs
 *
 *   METHOD \n PATH \n CANONICAL_QUERY \n TIMESTAMP \n NONCE \n hex(SHA256(body))
 *
 * with HMAC-SHA256 using the UTF-8 bytes of signKey as key, and sends X-Timestamp, X-Nonce and X-Signature (Base64).
 * Because the key depends on the token, a signature is only valid together with the token it was made for.
 */
@Component
public class RequestSignature {
    public static final String TIMESTAMP="X-Timestamp", NONCE="X-Nonce", SIGNATURE="X-Signature";
    private static final Logger log=LoggerFactory.getLogger(RequestSignature.class);
    private static final Pattern NONCE_PATTERN=Pattern.compile("[A-Za-z0-9-]{16,64}");
    private static final Pattern TIMESTAMP_PATTERN=Pattern.compile("[0-9]{1,15}");

    /** A request whose signature matched; the nonce is claimed only after the token is confirmed valid. */
    public record Signed(HttpServletRequest request,long timestamp,String nonce) {}

    private final boolean enabled;
    private final byte[] masterKey;
    private final long skewMillis;
    private final int maxBody;
    private final NonceStore nonces;

    public RequestSignature(@Value("${app.signature.enabled:true}") boolean enabled,
                            @Value("${app.signature.master-key:}") String masterKey,
                            @Value("${app.signature.max-skew:300s}") Duration skew,
                            @Value("${app.signature.max-body-size:64KB}") DataSize maxBody,
                            NonceStore nonces) {
        this.enabled=enabled; this.skewMillis=skew.toMillis(); this.maxBody=(int)maxBody.toBytes(); this.nonces=nonces;
        if(skewMillis<1000) throw new IllegalArgumentException("app.signature.max-skew 不能小于 1 秒");
        if(masterKey.isBlank()) {
            byte[] random=new byte[32]; new SecureRandom().nextBytes(random); this.masterKey=random;
            log.warn("app.signature.master-key (SIGN_MASTER_KEY) is not set; using a random key for this process. "
                +"Users must log in again after a restart, and multiple instances cannot share sessions.");
        } else {
            if(masterKey.length()<32) throw new IllegalArgumentException("app.signature.master-key 至少 32 个字符");
            this.masterKey=masterKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    public boolean enabled() { return enabled; }

    /** The signKey for a token, given the token's SHA-256 hex (as stored in auth_token). */
    public String signKey(String tokenHash) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(masterKey,"easymall-sign-key:v1:"+tokenHash));
    }

    /** Checks headers, timestamp and signature. Returns the request with its body cached so the controller can read it again. */
    public Signed verify(HttpServletRequest req,String tokenHash) throws IOException {
        String timestamp=req.getHeader(TIMESTAMP),nonce=req.getHeader(NONCE),signature=req.getHeader(SIGNATURE);
        if(timestamp==null || nonce==null || signature==null) throw error(401,"SIGNATURE_MISSING","携带 token 的请求必须签名：缺少 X-Timestamp、X-Nonce 或 X-Signature");
        if(!TIMESTAMP_PATTERN.matcher(timestamp).matches() || !NONCE_PATTERN.matcher(nonce).matches())
            throw error(401,"SIGNATURE_MISSING","X-Timestamp 须为毫秒时间戳，X-Nonce 须为 16..64 位字母数字或 -");
        long time=Long.parseLong(timestamp);
        if(Math.abs(System.currentTimeMillis()-time)>skewMillis) throw error(401,"SIGNATURE_EXPIRED","签名已过期或客户端时钟偏差过大");
        byte[] provided;
        try { provided=Base64.getDecoder().decode(signature); }
        catch(IllegalArgumentException e) { throw invalid(); }
        var cached=CachedBodyRequest.of(req,maxBody);
        String canonical;
        try { canonical=canonical(req.getMethod(),req.getRequestURI(),req.getQueryString(),time,nonce,cached.body()); }
        catch(IllegalArgumentException malformedQuery) { throw invalid(); }
        byte[] expected=hmac(signKey(tokenHash).getBytes(StandardCharsets.UTF_8),canonical);
        if(!MessageDigest.isEqual(expected,provided)) {
            log.debug("Signature mismatch for {} {}",req.getMethod(),req.getRequestURI());
            throw invalid();
        }
        return new Signed(cached,time,nonce);
    }

    /** Rejects a second use of the same nonce for the same token within the timestamp window. */
    public void claimNonce(String tokenHash,Signed signed) {
        // A replay is rejected as expired once now > timestamp + skew, so the nonce only needs to live that long.
        if(!nonces.claim(tokenHash.substring(0,16)+":"+signed.nonce(),signed.timestamp()+skewMillis))
            throw error(409,"NONCE_REPLAYED","请求已处理过（X-Nonce 重复），重试时请重新签名");
    }

    static String canonical(String method,String path,String rawQuery,long timestamp,String nonce,byte[] body) {
        return method.toUpperCase(Locale.ROOT)+"\n"+path+"\n"+canonicalQuery(rawQuery)+"\n"+timestamp+"\n"+nonce+"\n"+sha256Hex(body);
    }

    /** Decodes each pair, re-encodes it per RFC 3986 (unreserved characters only), then sorts by key and value. */
    static String canonicalQuery(String rawQuery) {
        if(rawQuery==null || rawQuery.isEmpty()) return "";
        var pairs=new ArrayList<String[]>();
        for(String part:rawQuery.split("&")) {
            if(part.isEmpty()) continue;
            int i=part.indexOf('=');
            String key=i<0?part:part.substring(0,i),value=i<0?"":part.substring(i+1);
            pairs.add(new String[]{encode(URLDecoder.decode(key,StandardCharsets.UTF_8)),encode(URLDecoder.decode(value,StandardCharsets.UTF_8))});
        }
        pairs.sort(Comparator.<String[],String>comparing(p->p[0]).thenComparing(p->p[1]));
        var out=new StringJoiner("&");
        for(var p:pairs) out.add(p[0]+"="+p[1]);
        return out.toString();
    }
    static String encode(String text) {
        return URLEncoder.encode(text,StandardCharsets.UTF_8).replace("+","%20").replace("*","%2A").replace("%7E","~");
    }
    static String sha256Hex(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sign(String signKey,String canonical) {
        return Base64.getEncoder().encodeToString(hmac(signKey.getBytes(StandardCharsets.UTF_8),canonical));
    }
    private static byte[] hmac(byte[] key,String text) {
        try {
            var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static BusinessException invalid() { return error(401,"SIGNATURE_INVALID","签名校验失败"); }
    private static BusinessException error(int status,String code,String message) { return new BusinessException(status,code,message); }

    /** Reads the body once (bounded) and serves it to everything downstream. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private CachedBodyRequest(HttpServletRequest request,byte[] body) { super(request); this.body=body; }
        static CachedBodyRequest of(HttpServletRequest request,int max) throws IOException {
            if(request.getContentLengthLong()>max) throw tooLarge(max);
            byte[] body=request.getInputStream().readNBytes(max+1);
            if(body.length>max) throw tooLarge(max);
            return new CachedBodyRequest(request,body);
        }
        private static BusinessException tooLarge(int max) { return error(413,"PAYLOAD_TOO_LARGE","请求体不能超过 "+max+" 字节"); }
        byte[] body() { return body; }
        @Override public ServletInputStream getInputStream() {
            var in=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b,int off,int len) { return in.read(b,off,len); }
                @Override public boolean isFinished() { return in.available()==0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() {
            String encoding=getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),encoding==null?StandardCharsets.UTF_8:Charset.forName(encoding)));
        }
    }
}
