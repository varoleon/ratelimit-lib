-- KEYS[1] = rate limit key (a Redis ZSET)
-- ARGV[1] = current timestamp in seconds, used as the score cutoff and the new entry's score
-- ARGV[2] = window size in seconds
-- ARGV[3] = limit
-- ARGV[4] = unique member id for this attempt
--
-- Returns 1 if the attempt is admitted, 0 if the limit was already reached.
-- Runs as a single Lua script so the cleanup/count/add sequence is atomic under concurrent callers.

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local member = ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)

local count = redis.call('ZCARD', key)
if count >= limit then
    return 0
end

redis.call('ZADD', key, now, member)
redis.call('EXPIRE', key, window)
return 1
