-- join.lua — add an entry to the live queue and return its rank, atomically.
--
-- KEYS[1] = waitlist:{id}:queue
-- ARGV[1] = member (waitlist_entry.id)
-- ARGV[2] = score  (queue_score, = join_sequence for a fresh join)
--
-- ZADD NX: if the member is already queued (a retried join), its score is
-- left untouched, so a retry can never undo a referral bump. That makes the
-- script idempotent, and it doubles as self-repair: a join whose ZADD failed
-- the first time is fixed by the client simply retrying.
--
-- Returns: { rank (0-based), queue_size, added (1 = new, 0 = was already there) }
local added = redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
local size = redis.call('ZCARD', KEYS[1])
return { rank, size, added }
