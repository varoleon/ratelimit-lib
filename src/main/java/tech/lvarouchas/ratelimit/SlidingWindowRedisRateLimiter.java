package tech.lvarouchas.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

/**
 * Sliding-window-log rate limiter backed by a per-key Redis ZSET, where each member is a single
 * attempt scored by its timestamp. The cleanup/count/add sequence runs as one Lua script so it is
 * atomic under concurrent callers for the same key.
 */
@Slf4j
public class SlidingWindowRedisRateLimiter implements RateLimiter {

    private static final RedisScript<Long> SCRIPT = loadScript();

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterConfig config;

    public SlidingWindowRedisRateLimiter(StringRedisTemplate redisTemplate, RateLimiterConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
    }

    @Override
    public boolean tryConsume(String key) {
        if (!config.isEnabled()) {
            return true;
        }

        String redisKey = config.getKeyPrefix() + key;
        long now = Instant.now().getEpochSecond();
        String member = now + "-" + UUID.randomUUID();

        try {
            Long admitted = redisTemplate.execute(SCRIPT, Collections.singletonList(redisKey),
                    String.valueOf(now), String.valueOf(config.getWindowSeconds()),
                    String.valueOf(config.getLimit()), member);
            return admitted != null && admitted == 1L;
        } catch (Exception e) {
            log.warn("Error while checking rate limit in Redis for key {}", key, e);
            return config.isFailOpen();
        }
    }

    @Override
    public void reset(String key) {
        try {
            redisTemplate.delete(config.getKeyPrefix() + key);
        } catch (Exception e) {
            log.warn("Error while resetting rate limit in Redis for key {}", key, e);
        }
    }

    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window_rate_limiter.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
