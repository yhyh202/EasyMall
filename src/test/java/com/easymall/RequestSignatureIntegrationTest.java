package com.easymall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.easymall.SignedRequests.signed;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("demo")
class RequestSignatureIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired Db db;

    record Session(long userId,String token,String signKey) {}
    Session session() {
        var c=new Requests.Credentials("s"+UUID.randomUUID().toString().replace("-",""),"SignerPass123!");
        auth.register(c); return login(c);
    }
    Session admin() { return login(new Requests.Credentials("admin","DemoAdmin123!")); }
    @SuppressWarnings("unchecked") Session login(Requests.Credentials c) {
        var r=auth.login(c);
        return new Session(((Number)((Map<String,Object>)r.get("user")).get("id")).longValue(),(String)r.get("token"),(String)r.get("signKey"));
    }
    MvcResult send(MockHttpServletRequestBuilder request,Session s,Object body,RequestPostProcessor... processors) throws Exception {
        if(s!=null) request.header("Authorization","Bearer "+s.token());
        if(body!=null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        for(var p:processors) request.with(p);
        return mvc.perform(request).andReturn();
    }
    JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsByteArray()); }
    void expect(MvcResult r,int status,String code) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(body(r).get("code").asText()).isEqualTo(code);
    }
    Requests.Address address() { return new Requests.Address("签名测试","13800138000","签名测试地址",false); }
    long addresses(Session s) { return db.rows("SELECT id FROM address WHERE user_id=?",s.userId()).size(); }

    @Test void loginReturnsSignKeyAndSignedRequestsPass() throws Exception {
        var s=session();
        assertThat(s.signKey()).matches("[A-Za-z0-9_-]{43}");
        var me=send(get("/api/auth/me"),s,null,signed(s.signKey()));
        expect(me,200,"OK");
        assertThat(body(me).at("/data/id").asLong()).isEqualTo(s.userId());
        expect(send(post("/api/addresses"),s,address(),signed(s.signKey())),200,"OK");
        expect(send(get("/api/orders?status=PENDING&page=1&size=5"),s,null,signed(s.signKey())),200,"OK");
    }

    @Test void requestsWithTokenMustBeSignedButPublicRequestsWithoutTokenNeedNoSignature() throws Exception {
        var s=session();
        expect(send(get("/api/auth/me"),s,null),401,"SIGNATURE_MISSING");
        expect(send(get("/api/categories"),s,null),401,"SIGNATURE_MISSING");
        expect(send(get("/api/categories"),null,null),200,"OK");
        expect(send(get("/api/auth/me"),null,null),401,"UNAUTHORIZED");
        expect(send(get("/api/auth/me"),s,null,r->{ r.addHeader(RequestSignature.TIMESTAMP,"abc"); r.addHeader(RequestSignature.NONCE,"short"); r.addHeader(RequestSignature.SIGNATURE,"x"); return r; }),401,"SIGNATURE_MISSING");
    }

    @Test void tamperingWithBodyQueryOrPathIsRejected() throws Exception {
        var s=session(); var other=session();
        byte[] changed=json.writeValueAsBytes(new Requests.Address("篡改","13800138000","篡改地址",false));
        expect(send(post("/api/addresses"),s,address(),signed(s.signKey()),r->{ r.setContent(changed); return r; }),401,"SIGNATURE_INVALID");
        assertThat(addresses(s)).isZero();
        expect(send(get("/api/orders?page=1&size=5"),s,null,signed(s.signKey()),r->{ r.setQueryString("page=2&size=5"); return r; }),401,"SIGNATURE_INVALID");
        expect(send(get("/api/orders/1"),s,null,signed(s.signKey()),r->{ r.setRequestURI("/api/orders/2"); return r; }),401,"SIGNATURE_INVALID");
        // Another login's key does not fit this token.
        expect(send(get("/api/auth/me"),s,null,signed(other.signKey())),401,"SIGNATURE_INVALID");
        expect(send(get("/api/auth/me"),s,null,r->{ r.addHeader(RequestSignature.TIMESTAMP,String.valueOf(System.currentTimeMillis()));
            r.addHeader(RequestSignature.NONCE,UUID.randomUUID().toString()); r.addHeader(RequestSignature.SIGNATURE,"%%not-base64%%"); return r; }),401,"SIGNATURE_INVALID");
    }

    @Test void queryParameterOrderAndEncodingStyleDoNotMatter() throws Exception {
        var s=admin(); long now=System.currentTimeMillis(); String nonce=UUID.randomUUID().toString();
        // The client signs one spelling of the query; the request carries another spelling of the same parameters.
        String canonical=RequestSignature.canonical("GET","/api/admin/products","page=1&size=5&keyword=%E6%BC%94%E7%A4%BA%20%E5%95%86%E5%93%81",now,nonce,new byte[0]);
        var result=send(get("/api/admin/products").param("keyword","演示 商品").param("size","5").param("page","1"),s,null,r->{
            r.setQueryString("keyword=%E6%BC%94%E7%A4%BA+%E5%95%86%E5%93%81&size=5&page=1");
            r.addHeader(RequestSignature.TIMESTAMP,String.valueOf(now)); r.addHeader(RequestSignature.NONCE,nonce);
            r.addHeader(RequestSignature.SIGNATURE,RequestSignature.sign(s.signKey(),canonical)); return r;
        });
        expect(result,200,"OK");
    }

    @Test void timestampsOutsideTheWindowAreRejected() throws Exception {
        var s=session(); long now=System.currentTimeMillis();
        expect(send(get("/api/auth/me"),s,null,signed(s.signKey(),now-301_000,UUID.randomUUID().toString())),401,"SIGNATURE_EXPIRED");
        expect(send(get("/api/auth/me"),s,null,signed(s.signKey(),now+301_000,UUID.randomUUID().toString())),401,"SIGNATURE_EXPIRED");
        expect(send(get("/api/auth/me"),s,null,signed(s.signKey(),now-200_000,UUID.randomUUID().toString())),200,"OK");
    }

    @Test void replayedRequestIsRejectedWithoutSideEffects() throws Exception {
        var s=session(); var other=session(); long now=System.currentTimeMillis(); String nonce=UUID.randomUUID().toString();
        expect(send(post("/api/addresses"),s,address(),signed(s.signKey(),now,nonce)),200,"OK");
        expect(send(post("/api/addresses"),s,address(),signed(s.signKey(),now,nonce)),409,"NONCE_REPLAYED");
        assertThat(addresses(s)).isEqualTo(1);
        // Nonces are scoped per token.
        expect(send(post("/api/addresses"),other,address(),signed(other.signKey(),now,nonce)),200,"OK");
        // A rejected signature does not use up the nonce.
        String fresh=UUID.randomUUID().toString();
        expect(send(get("/api/auth/me"),s,null,signed(other.signKey(),now,fresh)),401,"SIGNATURE_INVALID");
        expect(send(get("/api/auth/me"),s,null,signed(s.signKey(),now,fresh)),200,"OK");
    }

    @Test void concurrentReplaysHaveExactlyOneWinner() throws Exception {
        var s=session(); long now=System.currentTimeMillis(); String nonce=UUID.randomUUID().toString(); int threads=12;
        var statuses=new ArrayList<Integer>();
        try(var pool=Executors.newFixedThreadPool(threads)) {
            var gate=new CountDownLatch(1); var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<threads;i++) futures.add(pool.submit(()->{ gate.await(); return send(post("/api/addresses"),s,address(),signed(s.signKey(),now,nonce)).getResponse().getStatus(); }));
            gate.countDown();
            for(var f:futures) statuses.add(f.get(30,TimeUnit.SECONDS));
        }
        assertThat(statuses).containsOnly(200,409);
        assertThat(statuses.stream().filter(x->x==200).count()).isEqualTo(1);
        assertThat(addresses(s)).isEqualTo(1);
    }

    @Test void loggedOutTokenFailsEvenWithValidSignature() throws Exception {
        var s=session();
        expect(send(post("/api/auth/logout"),s,null,signed(s.signKey())),200,"OK");
        expect(send(get("/api/auth/me"),s,null,signed(s.signKey())),401,"UNAUTHORIZED");
    }

    @Test void oversizedBodyIsRejected() throws Exception {
        var s=session();
        var request=post("/api/addresses").header("Authorization","Bearer "+s.token()).contentType("application/json")
            .content(("{\"detail\":\""+"x".repeat(70*1024)+"\"}").getBytes(StandardCharsets.UTF_8)).with(signed(s.signKey()));
        expect(mvc.perform(request).andReturn(),413,"PAYLOAD_TOO_LARGE");
    }

    @Test void swaggerInitializerSignsRequests() throws Exception {
        var r=mvc.perform(get("/swagger-ui/swagger-initializer.js")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String script=r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).contains("X-Signature","requestInterceptor","responseInterceptor","window.SwaggerUIBundle = wrapped");
        // springdoc's own configuration is still there, after the signing wrapper.
        assertThat(script.indexOf("/v3/api-docs")).isGreaterThan(script.indexOf("window.SwaggerUIBundle = wrapped"));
    }
}
