-- snapshot.lua — a consistent view of one waitlist for the dashboard.
--
-- KEYS[1] = waitlist:{id}:queue
-- KEYS[2] = waitlist:{id}:reserved
-- KEYS[3] = waitlist:{id}:stats      (hash: confirmed, expired — Brick 3)
-- ARGV[1] = how many WAITING entries to return from the head
--
-- Returns { queue_size, reserved_size, {member, score, ...}, {member, expiry, ...}, {confirmed, expired} }
-- Both reads happen inside one script, so no join/promotion can land between them.
local limit = tonumber(ARGV[1])
local waiting = redis.call('ZRANGE', KEYS[1], 0, limit - 1, 'WITHSCORES')
local reserved = redis.call('ZRANGE', KEYS[2], 0, -1, 'WITHSCORES')
local stats = redis.call('HMGET', KEYS[3], 'confirmed', 'expired')
return { redis.call('ZCARD', KEYS[1]), redis.call('ZCARD', KEYS[2]), waiting, reserved,
         { stats[1] or '0', stats[2] or '0' } }
