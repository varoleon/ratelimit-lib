package tech.lvarouchas.ratelimit;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RateLimiterConfig {

    /** Max number of accepted calls per window. */
    private final int limit;

    /** Sliding window size, in seconds. */
    private final int windowSeconds;

    /** Prefix prepended to every Redis key, so limiters for different purposes don't collide. */
    @Builder.Default
    private final String keyPrefix = "ratelimit:";

    @Builder.Default
    private final boolean enabled = true;

    /** Whether to admit the request when Redis is unavailable or errors out. */
    @Builder.Default
    private final boolean failOpen = true;
}
