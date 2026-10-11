package tech.lvarouchas.ratelimit;

public interface RateLimiter {

    /**
     * Registers an attempt for the given key and reports whether it is within the configured limit.
     * Implementations must treat this as a single atomic check-and-increment: concurrent calls for
     * the same key must not both be admitted once the limit is reached.
     */
    boolean tryConsume(String key);

    /**
     * Clears all recorded attempts for the given key.
     */
    void reset(String key);
}
