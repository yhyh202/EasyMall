package com.easymall;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import redis.embedded.RedisServer;
import java.net.ServerSocket;
import java.util.*;
import static com.easymall.SignedRequests.signed;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Nonces in a real (embedded) Redis: shared replay protection, and fail-closed when Redis is down. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("demo")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisNonceStoreIntegrationTest {
    static int port;
    static RedisServer redis;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        redis=new RedisServer(port); redis.start();
        registry.add("spring.data.redis.port",()->port);
        registry.add("app.signature.nonce-store",()->"redis");
    }
    @AfterAll static void stopRedis() throws Exception { if(redis!=null && redis.isActive()) redis.stop(); }

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired StringRedisTemplate template;

    Map<String,Object> login() {
        var c=new Requests.Credentials("r"+UUID.randomUUID().toString().replace("-",""),"RedisPass123!");
        auth.register(c); return auth.login(c);
    }
    int me(Map<String,Object> login,long timestamp,String nonce) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+login.get("token"))
            .with(signed((String)login.get("signKey"),timestamp,nonce))).andReturn().getResponse().getStatus();
    }

    @Test @Order(1) void nonceIsStoredInRedisWithTtlAndReplayIsRejected() throws Exception {
        var login=login(); long now=System.currentTimeMillis(); String nonce=UUID.randomUUID().toString();
        assertThat(me(login,now,nonce)).isEqualTo(200);
        assertThat(me(login,now,nonce)).isEqualTo(409);
        String key=NonceStore.Redis.PREFIX+AuthService.hash((String)login.get("token")).substring(0,16)+":"+nonce;
        assertThat(template.hasKey(key)).isTrue();
        // Kept until timestamp + max-skew (300s), not longer.
        assertThat(template.getExpire(key)).isBetween(290L,300L);
    }

    @Test @Order(2) void redisOutageRejectsSignedRequests() throws Exception {
        var login=login();
        redis.stop();
        try {
            var result=mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+login.get("token"))
                .with(signed((String)login.get("signKey")))).andReturn().getResponse();
            assertThat(result.getStatus()).isEqualTo(503);
            assertThat(result.getContentAsString()).contains("NONCE_STORE_UNAVAILABLE");
            // Requests without a token do not touch the nonce store.
            assertThat(mvc.perform(get("/api/categories")).andReturn().getResponse().getStatus()).isEqualTo(200);
        } finally {
            redis=new RedisServer(port); redis.start();
        }
    }
}
