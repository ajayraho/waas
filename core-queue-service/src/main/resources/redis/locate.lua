-- locate.lua — where is this entry right now? One atomic read (ARCHITECTURE §21.8).
--
-- KEYS[1] = waitlist:{id}:queue      (WAITING, score = queue_score)
-- KEYS[2] = waitlist:{id}:reserved   (RESERVED, score = expiry epoch ms)
-- ARGV[1] = member
--
-- Returns one of:
--   { 'WAITING',  rank, queue_size, score }
--   { 'RESERVED', expiry_ms }
--   { 'ABSENT' }   -> caller falls back to Postgres (terminal state, or never joined)
--
-- Scores are returned as strings: Redis converts Lua numbers to integers,
-- which would silently truncate a midpoint score like 3.5.
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if rank then
    return { 'WAITING', rank, redis.call('ZCARD', KEYS[1]), redis.call('ZSCORE', KEYS[1], ARGV[1]) }
end
local expiry = redis.call('ZSCORE', KEYS[2], ARGV[1])
if expiry then
    return { 'RESERVED', expiry }
end
return { 'ABSENT' }
