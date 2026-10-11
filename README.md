# ratelimit-lib

A small, standalone Java library that provides a distributed, Redis-backed rate limiter. It is built on **Spring Data Redis** — the consuming application must already use a `StringRedisTemplate` to talk to Redis. It does not work with a raw Jedis/Lettuce client, and it is not tied to Spring Boot specifically (no auto-configuration, no application entry point) — any app that has a `StringRedisTemplate` bean available can use it.

## Purpose / Use case

Use this library when you need to cap how many times a given key (a user ID, API key, IP address, session ID, etc.) may perform an action within a rolling time window, **and that cap must hold across multiple application instances** sharing the same Redis deployment. Typical use cases: throttling login attempts, limiting calls to an expensive downstream API, protecting a chat/voicebot endpoint from abuse, or enforcing per-tenant quotas.

It is not a general-purpose throughput shaper (no burst/refill semantics, no queuing, no per-key dynamic limits) — it answers a single yes/no question per call: *"is this key still within its limit right now?"*.

A common use case is limiting the number of requests a single **user ID or username** can make within a window — e.g. at most 100 API calls per user per minute — regardless of which service instance handles the request, since the count is kept in shared Redis state. Pass the user ID/username as the `key` to `tryConsume`:

```java
if (!rateLimiter.tryConsume(username)) {
    // this user has exceeded their personal request limit for the current window
}
```

## Algorithm

`SlidingWindowRedisRateLimiter` implements a **sliding-window-log** algorithm:

- Each key maps to a Redis **ZSET** (`<keyPrefix><key>`). Every accepted attempt is added as one ZSET member, scored by the epoch-second timestamp it occurred at.
- On every `tryConsume(key)` call:
  1. Entries older than `now - windowSeconds` are evicted (`ZREMRANGEBYSCORE`).
  2. The remaining member count is compared against `limit` (`ZCARD`).
  3. If under the limit, the new attempt is recorded (`ZADD`) and the key's TTL is refreshed (`EXPIRE windowSeconds`) so idle keys clean themselves up automatically.
- All four steps run inside a **single Lua script** (`src/main/resources/scripts/sliding_window_rate_limiter.lua`), executed atomically by Redis. This is what makes the check-and-increment safe under concurrent callers for the same key — there is no read-then-write race between application instances.
- Each ZSET member is `<timestamp>-<random UUID>`, so multiple attempts within the same second never collide.

Trade-off to be aware of: memory per key is proportional to the number of attempts recorded within the current window (not O(1) like a fixed counter or token bucket), and time resolution is whole seconds. For very high-volume keys or sub-second precision, this algorithm may not be the right fit.

## Technology stack & requirements

- **Java 21**
- **Maven** build (`pom.xml`)
- **Spring Data Redis** (`spring-data-redis`, `provided` scope) — the consuming application supplies its own `StringRedisTemplate` bean and a compatible version at runtime; the library itself does not start or manage a Redis connection.
- **Lombok** (`provided` scope) — used for `RateLimiterConfig`'s `@Builder`/`@Getter`.
- **SLF4J** — logging facade; errors talking to Redis are logged as warnings, not thrown. No implementation is bundled, so it routes into whatever logging backend (Logback, Log4j2, etc.) the consuming application already uses.
- A reachable **Redis server** is required at runtime (the library does not embed or mock one).
- Test stack: JUnit 5 and Mockito (test scope only, not needed by consumers).

Because `spring-data-redis` and `lombok` are `provided`-scope, they are **not** pulled in transitively — the consuming project must declare its own compatible versions.

## Installation

Once published to a Maven repository of your choice:

```xml
<dependency>
    <groupId>tech.lvarouchas</groupId>
    <artifactId>ratelimit-lib</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Your project must also provide `spring-data-redis` and a configured `StringRedisTemplate` bean, plus Lombok on the compile classpath.

## Usage

```java
RateLimiterConfig config = RateLimiterConfig.builder()
        .limit(100)                // max accepted calls per window
        .windowSeconds(60)          // rolling window size
        .keyPrefix("login-attempts:") // default is "ratelimit:"
        .failOpen(true)             // admit requests if Redis is unreachable (default)
        .enabled(true)              // set false to disable rate limiting entirely (default)
        .build();

RateLimiter rateLimiter = new SlidingWindowRedisRateLimiter(stringRedisTemplate, config);

if (!rateLimiter.tryConsume(userId)) {
    // limit reached for this key within the current window — reject/throttle the caller
}

// Clear all recorded attempts for a key (e.g. after a manual unblock):
rateLimiter.reset(userId);
```

### Configuration (`RateLimiterConfig`)

| Field           | Default         | Description                                                             |
|-----------------|-----------------|---------------------------------------------------------------------------|
| `limit`         | —(required)     | Max accepted calls per window.                                          |
| `windowSeconds` | —(required)     | Sliding window size, in seconds.                                        |
| `keyPrefix`     | `"ratelimit:"`  | Prepended to every Redis key, so limiters for different purposes don't collide. |
| `enabled`       | `true`          | When `false`, `tryConsume` always returns `true` without touching Redis. |
| `failOpen`      | `true`          | When Redis errors out, `tryConsume` returns this value instead of throwing. Set `false` to fail closed (reject) on Redis errors. |

### Error handling

- `tryConsume` never throws on a Redis failure — it logs a warning and returns `config.isFailOpen()`.
- `reset` never throws on a Redis failure — it logs a warning and returns silently.

This means the library is safe to wire into a request path even if Redis availability is uncertain; the `failOpen` flag lets you choose whether an outage should let traffic through or block it.

## Deciding if this library fits

Use it if you need:
- A rate limit enforced consistently **across multiple instances** of your service (shared Redis state).
- Atomic, race-free check-and-increment semantics per key.
- Automatic cleanup of idle keys (via Redis `EXPIRE`).
- Configurable fail-open/fail-closed behavior for Redis outages.

Consider alternatives if you need:
- In-memory-only limiting with no shared/external state.
- Sub-second precision or extremely high request volumes per key (where ZSET member growth would be a concern).
- Token-bucket-style bursting/refill behavior rather than a strict sliding window count.
