package com.easymall;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import redis.embedded.RedisServer;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Runs against a real (embedded) Redis server with the search count cache enabled. */
@SpringBootTest @ActiveProfiles("demo")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchCountCacheIntegrationTest {
    static int port;
    static RedisServer redis;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        redis=new RedisServer(port); redis.start();
        registry.add("spring.data.redis.port",()->port);
        registry.add("app.search-cache.enabled",()->"true");
        registry.add("app.search-cache.failure-backoff",()->"1s");
        // Separate in-memory database so totals are not affected by other test classes.
        registry.add("spring.datasource.url",()->"jdbc:h2:mem:easymall_cache;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
    }
    @AfterAll static void stopRedis() throws Exception { if(redis!=null && redis.isActive()) redis.stop(); }

    @Autowired CatalogService catalog;
    @Autowired SearchCountCache cache;
    @Autowired StringRedisTemplate template;
    @Autowired TransactionTemplate tx;
    @Autowired Db db;

    String keyword() { return "cache-"+UUID.randomUUID(); }
    long category() { return Db.number(catalog.categories().getFirst(),"id"); }
    long product(String name) { return Db.number(catalog.saveProduct(null,new Requests.Product(category(),name,"test",new BigDecimal("1.00"),true)),"id"); }
    long publicTotal(String keyword) { return catalog.products(keyword,null,false,1,20).total(); }
    String generation() { return template.opsForValue().get(SearchCountCache.GENERATION_KEY); }

    @Test @Order(1) void totalIsCachedAndProductChangesInvalidateIt() {
        String kw=keyword(); long a=product(kw+"-a"); product(kw+"-b");
        assertThat(publicTotal(kw)).isEqualTo(2);
        // A write that bypasses the application is not seen until TTL: proves the total comes from Redis.
        db.update("INSERT INTO product(category_id,name,description,price,active,stock) VALUES (?,?,'raw',1.00,TRUE,0)",category(),kw+"-raw");
        assertThat(publicTotal(kw)).isEqualTo(2);
        assertThat(catalog.products(kw,null,false,1,20).items()).hasSize(3);
        // Admin listing is never cached.
        assertThat(catalog.products(kw,null,true,1,20).total()).isEqualTo(3);
        long c=product(kw+"-c");
        assertThat(publicTotal(kw)).isEqualTo(4);
        catalog.deactivate(c);
        assertThat(publicTotal(kw)).isEqualTo(3);
        catalog.saveProduct(a,new Requests.Product(category(),"renamed-"+UUID.randomUUID(),"test",new BigDecimal("1.00"),true));
        assertThat(publicTotal(kw)).isEqualTo(2);
        // Category filter and keyword filter use distinct keys.
        assertThat(catalog.products(kw,category(),false,1,20).total()).isEqualTo(2);
        assertThat(catalog.products(kw,category()+999,false,1,20).total()).isZero();
    }

    @Test @Order(2) void invalidationHappensOnlyAfterCommit() {
        String before=generation();
        tx.executeWithoutResult(s->{
            product(keyword());
            assertThat(generation()).isEqualTo(before);
        });
        String committed=generation();
        assertThat(committed).isNotEqualTo(before);
        tx.executeWithoutResult(s->{ product(keyword()); s.setRollbackOnly(); });
        assertThat(generation()).isEqualTo(committed);
    }

    @Test @Order(3) void concurrentMissesQueryTheDatabaseOnce() throws Exception {
        String kw=keyword(); var calls=new AtomicInteger();
        int threads=16; var results=new ArrayList<Future<Long>>();
        try(var pool=Executors.newFixedThreadPool(threads)) {
            var gate=new CountDownLatch(1);
            for(int i=0;i<threads;i++) results.add(pool.submit(()->{
                gate.await();
                return cache.count(kw,null,()->{ calls.incrementAndGet(); sleep(300); return 7; });
            }));
            gate.countDown();
            for(var r:results) assertThat(r.get(10,TimeUnit.SECONDS)).isEqualTo(7);
        }
        assertThat(calls).hasValue(1);
        assertThat(cache.count(kw,null,()->{ calls.incrementAndGet(); return 0; })).isEqualTo(7);
        assertThat(calls).hasValue(1);
    }

    @Test @Order(4) void redisOutageFallsBackToDatabaseAndRecovers() throws Exception {
        String kw=keyword(); product(kw+"-a");
        assertThat(publicTotal(kw)).isEqualTo(1);
        redis.stop();
        try {
            product(kw+"-b"); // invalidation cannot reach Redis
            long start=System.nanoTime();
            for(int i=0;i<20;i++) assertThat(publicTotal(kw)).isEqualTo(2);
            // One timeout, then Redis is skipped during back-off instead of timing out on every request.
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(3000);
        } finally {
            redis=new RedisServer(port); redis.start();
        }
        // After back-off, the pending invalidation is applied before the cache is used again.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(safeGeneration()==null && System.nanoTime()<deadline) { assertThat(publicTotal(kw)).isEqualTo(2); sleep(200); }
        assertThat(generation()).isNotNull();
        assertThat(publicTotal(kw)).isEqualTo(2);
    }
    String safeGeneration() { try { return generation(); } catch(RuntimeException reconnecting) { return null; } }

    static void sleep(long millis) {
        try { Thread.sleep(millis); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
