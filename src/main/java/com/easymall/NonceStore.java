package com.easymall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Remembers request nonces until their signature window ends. */
public interface NonceStore {
    /**
     * Records the nonce. Returns false if it was already recorded and has not expired.
     * Throws a 503 BusinessException when the store cannot answer: a signature check must fail closed.
     */
    boolean claim(String key,long expiresAtMillis);

    static BusinessException unavailable() {
        return new BusinessException(503,"NONCE_STORE_UNAVAILABLE","签名校验暂不可用，请稍后重试");
    }

    /** Single-instance store. Expired entries are swept at most every 10 seconds; the size is bounded. */
    final class Memory implements NonceStore {
        private final ConcurrentHashMap<String,Long> entries=new ConcurrentHashMap<>();
        private final AtomicLong nextSweep=new AtomicLong();
        private final int capacity;
        public Memory(int capacity) {
            if(capacity<1) throw new IllegalArgumentException("app.signature.memory-nonce-capacity 必须大于 0");
            this.capacity=capacity;
        }
        @Override public boolean claim(String key,long expiresAtMillis) {
            long now=System.currentTimeMillis(),sweep=nextSweep.get();
            if(now>=sweep && nextSweep.compareAndSet(sweep,now+10_000)) entries.values().removeIf(expiry->expiry<=now);
            if(entries.size()>=capacity && !entries.containsKey(key)) throw unavailable();
            boolean[] claimed={false};
            entries.compute(key,(k,old)->{
                if(old!=null && old>now) return old;
                claimed[0]=true; return expiresAtMillis;
            });
            return claimed[0];
        }
        int size() { return entries.size(); }
    }

    /** Shared store for multiple instances: SET key NX PX ttl. */
    final class Redis implements NonceStore {
        static final String PREFIX="easymall:sign-nonce:";
        private static final Logger log=LoggerFactory.getLogger(Redis.class);
        private final StringRedisTemplate redis;
        public Redis(StringRedisTemplate redis) { this.redis=redis; }
        @Override public boolean claim(String key,long expiresAtMillis) {
            long ttl=Math.max(1,expiresAtMillis-System.currentTimeMillis());
            Boolean claimed;
            try { claimed=redis.opsForValue().setIfAbsent(PREFIX+key,"1",Duration.ofMillis(ttl)); }
            catch(RuntimeException e) { log.warn("Nonce store unavailable: {}",e.toString()); throw unavailable(); }
            if(claimed==null) throw unavailable();
            return claimed;
        }
    }
}
