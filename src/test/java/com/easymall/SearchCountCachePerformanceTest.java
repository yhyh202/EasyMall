package com.easymall;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Compares the public keyword search with the Redis count cache off and on.
 *
 * Opt-in only (timing based, takes about two minutes):
 *   mvn test -Dtest=SearchCountCachePerformanceTest -Dperf=true
 * Against MySQL instead of H2, set PERF_DB_URL, PERF_DB_USERNAME, PERF_DB_PASSWORD (or the -Dperf.db.* properties).
 * Optional: -Dperf.levels=1,8,32,64 -Dperf.seconds=10 -Dperf.warmup=3 -Dperf.products=10000
 *
 * Both modes call CatalogService.products with the same Db and data; only the SearchCountCache differs.
 * HTTP, security and JSON overhead are excluded because they are identical in both modes.
 */
@SpringBootTest @ActiveProfiles("demo")
@EnabledIfSystemProperty(named="perf",matches="true")
class SearchCountCachePerformanceTest {
    static final int PRODUCTS=Integer.getInteger("perf.products",10000);
    static final int WARMUP=Integer.getInteger("perf.warmup",3);
    static final int SECONDS=Integer.getInteger("perf.seconds",10);
    static final List<Integer> LEVELS=Arrays.stream(System.getProperty("perf.levels","1,8,32,64").split(",")).map(String::trim).map(Integer::valueOf).toList();
    static final String KEYWORD="catalog";
    static final boolean MYSQL=setting("perf.db.url")!=null;
    /** System property, or the matching environment variable (perf.db.url → PERF_DB_URL); URLs with '&' survive mvn.cmd only via the latter. */
    static String setting(String name) {
        String value=System.getProperty(name);
        return value!=null?value:System.getenv(name.toUpperCase(Locale.ROOT).replace('.','_'));
    }
    static int port;
    static RedisServer redis;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        redis=new RedisServer(port); redis.start();
        registry.add("spring.data.redis.port",()->port);
        registry.add("logging.level.root",()->"WARN");
        if(MYSQL) {
            registry.add("spring.datasource.url",()->setting("perf.db.url"));
            registry.add("spring.datasource.username",()->Objects.requireNonNullElse(setting("perf.db.username"),"root"));
            registry.add("spring.datasource.password",()->Objects.requireNonNullElse(setting("perf.db.password"),""));
            registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        } else {
            registry.add("spring.datasource.url",()->"jdbc:h2:mem:easymall_perf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        }
    }
    @AfterAll static void stopRedis() throws Exception { if(redis!=null && redis.isActive()) redis.stop(); }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate template;

    /** Counts how many COUNT(*) statements actually reach the database. */
    static class CountingDb extends Db {
        final AtomicLong counts=new AtomicLong();
        CountingDb(JdbcTemplate jdbc) { super(jdbc); }
        @Override public long count(String table,String where,List<Object> args) { counts.incrementAndGet(); return super.count(table,where,args); }
    }
    record Result(String scenario,int threads,long requests,double perSecond,double p50,double p95,double p99,long dbCounts,long errors,long invalidations) {}

    @Test void compareCacheOffAndOn() throws Exception {
        long category=prepareData();
        var db=new CountingDb(jdbc);
        var off=new CatalogService(db,new SearchCountCache(template,false,Duration.ofSeconds(60),Duration.ofSeconds(5)));
        var cache=new SearchCountCache(template,true,Duration.ofSeconds(60),Duration.ofSeconds(5));
        var on=new CatalogService(db,cache);
        var results=new ArrayList<Result>();
        for(int threads:LEVELS) {
            results.add(run("缓存关闭",threads,off,category,db,null));
            cache.invalidateAfterCommit(); // start every cached run cold; the miss happens during warm-up
            results.add(run("缓存开启",threads,on,category,db,null));
        }
        int max=LEVELS.getLast();
        cache.invalidateAfterCommit();
        results.add(run("缓存开启，每秒 5 次失效",max,on,category,db,cache));
        redis.stop();
        try { results.add(run("缓存开启，Redis 宕机",max,on,category,db,null)); }
        finally { redis=new RedisServer(port); redis.start(); }
        report(results);

        for(var r:results) {
            // Baseline and outage runs are DB-bound; their pool timeouts are a finding to report, not a test failure.
            boolean dbBound=r.scenario().equals("缓存关闭") || r.scenario().contains("宕机");
            if(!dbBound) assertThat(r.errors()).as(r.scenario()+" 错误数").isZero();
            assertThat(r.requests()).as(r.scenario()+" 请求数").isPositive();
            // Requests straddling the start/end of the window may be counted on one side only.
            if(dbBound) assertThat(r.dbCounts()).isBetween(r.requests()-r.threads(),r.requests()+r.threads());
            if(r.scenario().equals("缓存开启")) assertThat(r.dbCounts()).as("热缓存下 COUNT 不应回源").isLessThanOrEqualTo(1);
            if(r.scenario().contains("失效")) assertThat(r.dbCounts()).as("每次失效最多回源一次").isLessThanOrEqualTo(r.invalidations()+2);
        }
    }

    long prepareData() {
        String name="perf-"+UUID.randomUUID();
        jdbc.update("INSERT INTO category(name) VALUES (?)",name);
        long category=jdbc.queryForObject("SELECT id FROM category WHERE name=?",Long.class,name);
        var rows=new ArrayList<Object[]>();
        for(int i=1;i<=PRODUCTS;i++) rows.add(new Object[]{category,KEYWORD+" product "+i,"performance test",new BigDecimal("12.30")});
        jdbc.batchUpdate("INSERT INTO product(category_id,name,description,price,active,stock) VALUES (?,?,?,?,TRUE,0)",rows);
        return category;
    }

    Result run(String scenario,int threads,CatalogService service,long category,CountingDb db,SearchCountCache invalidate) throws Exception {
        var stop=new AtomicBoolean(); var measuring=new AtomicBoolean();
        var errors=new AtomicLong(); var firstError=new AtomicReference<Throwable>(); var invalidations=new AtomicLong();
        var latencies=new long[threads][]; var sizes=new int[threads];
        var workers=Executors.newFixedThreadPool(threads);
        var background=Executors.newSingleThreadScheduledExecutor();
        try {
            for(int t=0;t<threads;t++) {
                final int index=t; latencies[t]=new long[4096];
                workers.submit(()->{
                    while(!stop.get()) {
                        long start=System.nanoTime(); boolean ok=true;
                        try {
                            var page=service.products(KEYWORD,category,false,1,20);
                            if(page.total()!=PRODUCTS || page.items().size()!=20) throw new AssertionError("total="+page.total()+" items="+page.items().size());
                        } catch(Throwable e) { ok=false; if(measuring.get()) { errors.incrementAndGet(); firstError.compareAndSet(null,e); } }
                        long elapsed=System.nanoTime()-start;
                        if(ok && measuring.get()) {
                            if(sizes[index]==latencies[index].length) latencies[index]=Arrays.copyOf(latencies[index],sizes[index]*2);
                            latencies[index][sizes[index]++]=elapsed;
                        }
                    }
                });
            }
            if(invalidate!=null) background.scheduleAtFixedRate(()->{ invalidate.invalidateAfterCommit(); if(measuring.get()) invalidations.incrementAndGet(); },200,200,TimeUnit.MILLISECONDS);
            Thread.sleep(WARMUP*1000L);
            db.counts.set(0); measuring.set(true); long begin=System.nanoTime();
            Thread.sleep(SECONDS*1000L);
            measuring.set(false); long end=System.nanoTime(); long counts=db.counts.get();
            stop.set(true);
            workers.shutdown(); assertThat(workers.awaitTermination(30,TimeUnit.SECONDS)).isTrue();
            if(firstError.get()!=null) firstError.get().printStackTrace();
            long total=0; for(int s:sizes) total+=s;
            long[] all=new long[(int)total]; int at=0;
            for(int t=0;t<threads;t++) { System.arraycopy(latencies[t],0,all,at,sizes[t]); at+=sizes[t]; }
            Arrays.sort(all);
            double seconds=(end-begin)/1e9;
            var r=new Result(scenario,threads,total+errors.get(),total/seconds,ms(all,0.50),ms(all,0.95),ms(all,0.99),counts,errors.get(),invalidations.get());
            System.out.printf("%s threads=%d rps=%.1f p95=%.2fms counts=%d errors=%d%n",scenario,threads,r.perSecond(),r.p95(),counts,r.errors());
            return r;
        } finally {
            stop.set(true); workers.shutdownNow(); background.shutdownNow();
        }
    }
    static double ms(long[] sorted,double q) { return sorted.length==0?0:sorted[Math.min(sorted.length-1,(int)Math.ceil(q*sorted.length)-1)]/1e6; }

    void report(List<Result> results) throws Exception {
        var out=new StringBuilder();
        out.append("# 搜索总数缓存性能对比（").append(MYSQL?"MySQL":"H2").append("）\n\n");
        out.append("数据：同一分类下 ").append(PRODUCTS).append(" 个名称含 `").append(KEYWORD).append("` 的在售商品；查询 keyword=").append(KEYWORD).append("&categoryId=…&page=1&size=20。");
        out.append("每组预热 ").append(WARMUP).append(" 秒、测量 ").append(SECONDS).append(" 秒，闭环无思考时间，连接池 20。");
        out.append("延迟只统计成功请求；错误多为等连接池超过 10 秒。\n\n");
        out.append("| 场景 | 并发 | 请求/秒 | p50 ms | p95 ms | p99 ms | 回源 COUNT 次数 | 测量请求数 | 错误 |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for(var r:results) out.append(String.format("| %s | %d | %.1f | %.2f | %.2f | %.2f | %d | %d | %d |%n",r.scenario(),r.threads(),r.perSecond(),r.p50(),r.p95(),r.p99(),r.dbCounts(),r.requests(),r.errors()));
        out.append("\n| 并发 | 请求/秒倍数（开/关） | p95 关 → 开 |\n|---:|---:|---|\n");
        for(int i=0;i+1<results.size();i+=2) {
            var a=results.get(i); var b=results.get(i+1);
            if(!a.scenario().equals("缓存关闭")) break;
            out.append(String.format("| %d | %.1fx | %.2f → %.2f ms |%n",a.threads(),b.perSecond()/a.perSecond(),a.p95(),b.p95()));
        }
        System.out.println(out);
        var file=Path.of("target","search-cache-perf-"+(MYSQL?"mysql":"h2")+".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file,out,StandardCharsets.UTF_8);
        System.out.println("报告已写入 "+file.toAbsolutePath());
    }
}
