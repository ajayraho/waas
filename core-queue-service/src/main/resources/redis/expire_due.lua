-- expire_due.lua — take every lapsed reservation out of the reserved set (§21.2).
--
-- KEYS[1] = waitlist:{id}:reserved
-- ARGV[1] = now (epoch ms)
-- ARGV[2] = max members per call (bounds the time the script holds Redis)
--
-- The set is scored by expiry, so "who is due?" is a range query:
-- O(log N + due), not a scan of every reservation.
--
-- Reading the due members and removing them happen in one atomic step. With several
-- Core instances sweeping at once, each lapsed member is returned to exactly one of
-- them. That is the single-consumer guarantee without a distributed lock.
--
-- Returns the members removed.
local due = redis.call('ZRANGE', KEYS[1], '-inf', ARGV[1], 'BYSCORE', 'LIMIT', 0, tonumber(ARGV[2]))
if #due > 0 then
    redis.call('ZREM', KEYS[1], unpack(due))
end
return due
