-- bump.lua — move a WAITING entry up by N *positions* (ARCHITECTURE §21.3).
--
-- KEYS[1] = waitlist:{id}:queue
-- ARGV[1] = member
-- ARGV[2] = N (spots requested)
--
-- Why rank-based: queue scores come from a global sequence, so they're sparse within
-- one waitlist (neighbours can be thousands apart). "score - N" would often move a
-- user zero places. Instead we find the member currently N ranks ahead and slot in
-- just in front of it: halfway between it and the one before it.
--
-- The rank lookup and the write happen in one atomic script. That makes "is this
-- user still WAITING?" and "apply the bump" a single decision (§21.4), so no
-- concurrent promotion can slip in between.
--
-- Returns:
--   { 'BANK' }                          not in the queue (RESERVED / done / absent): bank all N
--   { 'APPLIED', score, rank, moved }   moved = spots actually gained (<= N); caller banks N - moved
--                                       score is read back with ZSCORE so it's full precision
--   { 'PRECISION' }                     the gap is exhausted (~50 halvings); bank all N
local r = redis.call('ZRANK', KEYS[1], ARGV[1])
if not r then
    return { 'BANK' }
end
local n = tonumber(ARGV[2])
local target = r - n
if target < 0 then
    target = 0
end
if target == r then
    return { 'APPLIED', redis.call('ZSCORE', KEYS[1], ARGV[1]), r, 0 }  -- already at the head
end

local newScore
local ahead = redis.call('ZRANGE', KEYS[1], target, target, 'WITHSCORES')
local b = tonumber(ahead[2])
if target == 0 then
    newScore = b - 1
else
    local before = redis.call('ZRANGE', KEYS[1], target - 1, target - 1, 'WITHSCORES')
    local a = tonumber(before[2])
    newScore = a + (b - a) / 2
    if newScore <= a or newScore >= b then
        return { 'PRECISION' }
    end
end

redis.call('ZADD', KEYS[1], newScore, ARGV[1])
return { 'APPLIED', redis.call('ZSCORE', KEYS[1], ARGV[1]), redis.call('ZRANK', KEYS[1], ARGV[1]), r - target }
