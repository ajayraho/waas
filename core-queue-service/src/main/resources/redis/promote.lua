-- promote.lua — fill free serving slots from the head of the queue (ARCHITECTURE §21.1).
--
-- KEYS[1] = waitlist:{id}:queue      (WAITING, score = queue_score)
-- KEYS[2] = waitlist:{id}:reserved   (RESERVED, score = expiry epoch ms)
-- ARGV[1] = serving_capacity
-- ARGV[2] = now (epoch ms)
-- ARGV[3] = reservation window (ms)
--
-- Invariant: ZCARD(reserved) <= capacity, and members are promoted in queue order.
-- The "is there a free slot?" check and the "take it" write happen inside one
-- script, so two concurrent callers can never both see the last free slot.
-- That is the whole double-reservation race, closed.
--
-- Idempotent: with no free slot, or an empty queue, it does nothing. So it is safe
-- to call after every event that might free a slot, and again from the sweeper tick
-- as a safety net.
--
-- Returns { expiry_ms, member1, member2, ... } (just { expiry_ms } if nobody moved).
local capacity = tonumber(ARGV[1])
local expiry = tonumber(ARGV[2]) + tonumber(ARGV[3])
local result = { tostring(expiry) }
while redis.call('ZCARD', KEYS[2]) < capacity do
    local head = redis.call('ZPOPMIN', KEYS[1])
    if #head == 0 then
        break
    end
    redis.call('ZADD', KEYS[2], expiry, head[1])
    result[#result + 1] = head[1]
end
return result
