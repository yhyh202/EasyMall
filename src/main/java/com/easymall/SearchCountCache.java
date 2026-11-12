package com.easymall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/**
 * Caches the COUNT(*) behind the public product search in Redis.
 *
 * Invalidation uses a generation number: every cache key embeds the current generation, and any
 * product change increments it after commit, so all older entries become unreachable at once and
 * expire by TTL. The generation is read before the database COUNT runs, so a count computed from
 * pre-change data can only ever be written under an already-retired generation.
 *
 * Redis is optional for correctness: any Redis error falls back to the database and Redis is
 * skipped for a short back-off period, so an outage costs at most one timeout per period.
 */
@Component
public class SearchCountCache {
    static final String PREFIX="easymall:product-count:";
    static final String GENERATION_KEY=PREFIX+"gen";
    private static final Logger log=LoggerFactory.getLogger(SearchCountCache.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final long ttlMillis;
    private final long backoffNanos;
    private final ConcurrentHashMap<String,CompletableFuture<Long>> inFlight=new ConcurrentHashMap<>();
    private volatile long skipRedisUntil=System.nanoTime();
    private volatile boolean redisDown;
    /** Set when an invalidation could not reach Redis; the cache is bypassed until it succeeds. */
    private volatile boolean invalidationPending;

    public SearchCountCache(StringRedisTemplate redis,
                            @Value("${app.search-cache.enabled:false}") boolean enabled,
                            @Value("${app.search-cache.ttl:60s}") Duration ttl,
                            @Value("${app.search-cache.failure-backoff:5s}") Duration backoff) {
        this.redis=redis; this.enabled=enabled; this.ttlMillis=ttl.toMillis(); this.backoffNanos=backoff.toNanos();
        if(ttlMillis<1000) throw new IllegalArgumentException("app.search-cache.ttl 不能小于 1 秒");
    }

    /** Returns the public product total for the given filters, using the cache when possible. */
    public long count(String keyword,Long categoryId,LongSupplier database) {
        if(!enabled || !redisUsable()) return database.getAsLong();
        String key;
        try {
            if(invalidationPending) bumpGeneration();
            String generation=redis.opsForValue().get(GENERATION_KEY);
            key=PREFIX+"v"+(generation==null?"0":generation)+":c"+(categoryId==null?"all":categoryId)
                +":k"+(keyword==null?"-":AuthService.hash(keyword));
            String cached=redis.opsForValue().get(key);
            if(cached!=null) { recovered(); return Long.parseLong(cached); }
        } catch(RuntimeException e) {
            failed(e);
            return database.getAsLong();
        }
        return loadOnce(key,database);
    }

    /** Only one thread per key queries the database on a miss; the others wait for its result. */
    private long loadOnce(String key,LongSupplier database) {
        var mine=new CompletableFuture<Long>();
        var running=inFlight.putIfAbsent(key,mine);
        if(running!=null) {
            try { return running.join(); }
            catch(CompletionException e) { return database.getAsLong(); }
        }
        try {
            long total=database.getAsLong();
            mine.complete(total);
            try {
                // Up to 20% jitter so entries created together do not all expire together.
                long ttl=ttlMillis+ThreadLocalRandom.current().nextLong(ttlMillis/5+1);
                redis.opsForValue().set(key,Long.toString(total),Duration.ofMillis(ttl));
                recovered();
            } catch(RuntimeException e) { failed(e); }
            return total;
        } catch(RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key,mine);
        }
    }

    /** Invalidates every cached total once the current transaction commits (or now, if none). */
    public void invalidateAfterCommit() {
        if(!enabled) return;
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(); }
            });
        } else invalidate();
    }

    private void invalidate() {
        invalidationPending=true;
        if(!redisUsable()) return;
        try { bumpGeneration(); recovered(); }
        catch(RuntimeException e) { failed(e); }
    }

    private void bumpGeneration() {
        redis.opsForValue().increment(GENERATION_KEY);
        invalidationPending=false;
    }

    private boolean redisUsable() { return System.nanoTime()-skipRedisUntil>=0; }

    private void failed(RuntimeException e) {
        skipRedisUntil=System.nanoTime()+backoffNanos;
        if(!redisDown) { redisDown=true; log.warn("Redis 不可用，商品搜索总数暂时直接查询数据库: {}",e.toString()); }
    }

    private void recovered() {
        if(redisDown) { redisDown=false; log.info("Redis 已恢复，商品搜索总数缓存重新启用"); }
    }
}
