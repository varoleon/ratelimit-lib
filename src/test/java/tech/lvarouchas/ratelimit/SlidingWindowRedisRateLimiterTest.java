package tech.lvarouchas.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlidingWindowRedisRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private SlidingWindowRedisRateLimiter rateLimiter;

    @BeforeEach
    void setup() {
        RateLimiterConfig config = RateLimiterConfig.builder()
                .limit(100)
                .windowSeconds(10)
                .keyPrefix("test:")
                .build();
        rateLimiter = new SlidingWindowRedisRateLimiter(redisTemplate, config);
    }

    @Test
    void admitsRequestWhenScriptReturnsOne() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(rateLimiter.tryConsume("user-1"));
    }

    @Test
    void rejectsRequestWhenScriptReturnsZero() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        assertFalse(rateLimiter.tryConsume("user-1"));
    }

    @Test
    void failsOpenByDefaultWhenRedisErrors() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("boom"));

        assertTrue(rateLimiter.tryConsume("user-1"));
    }

    @Test
    void failsClosedWhenConfigured() {
        RateLimiterConfig config = RateLimiterConfig.builder()
                .limit(100)
                .windowSeconds(10)
                .failOpen(false)
                .build();
        rateLimiter = new SlidingWindowRedisRateLimiter(redisTemplate, config);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("boom"));

        assertFalse(rateLimiter.tryConsume("user-1"));
    }

    @Test
    void skipsRedisWhenDisabled() {
        RateLimiterConfig config = RateLimiterConfig.builder()
                .limit(100)
                .windowSeconds(10)
                .enabled(false)
                .build();
        rateLimiter = new SlidingWindowRedisRateLimiter(redisTemplate, config);

        assertTrue(rateLimiter.tryConsume("user-1"));

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void resetDeletesPrefixedKey() {
        rateLimiter.reset("user-1");

        verify(redisTemplate, times(1)).delete("test:user-1");
    }
}
