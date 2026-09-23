-- rebuild_swap.lua — publish a ZSET rebuilt from Postgres (ARCHITECTURE §21.6).
--
-- KEYS[1] = live key          e.g. waitlist:{id}:queue
-- KEYS[2] = staging key       e.g. waitlist:{id}:queue:rebuild
--
-- The rebuilder fills the staging key off to the side, then calls this script.
--   • live key missing (normal case): RENAME. Readers go from "no queue" to the
--     complete queue in one step and never see a half-built one.
--   • live key exists (a join landed on another instance mid-rebuild): merge
--     with AGGREGATE MIN. A member in both keeps the lower score, which is the
--     one carrying any referral bump. Nothing already live is lost.
-- Both keys share the {id} hash tag, so this works unchanged on Redis Cluster.
--
-- Returns 'RENAMED', 'MERGED' or 'EMPTY' (nothing staged).
if redis.call('EXISTS', KEYS[2]) == 0 then
    return 'EMPTY'
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('RENAME', KEYS[2], KEYS[1])
    return 'RENAMED'
end
redis.call('ZUNIONSTORE', KEYS[1], 2, KEYS[1], KEYS[2], 'AGGREGATE', 'MIN')
redis.call('DEL', KEYS[2])
return 'MERGED'
