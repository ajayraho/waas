-- confirm.lua — the user acts inside their checkout window (ARCHITECTURE §21.2).
--
-- KEYS[1] = waitlist:{id}:reserved
-- ARGV[1] = member
-- ARGV[2] = now (epoch ms)
--
-- Returns  1 : confirmed in time. The slot is released (ZREM) and the caller promotes the next entry.
--         -1 : the window had already lapsed. Nothing changes; the sweeper will expire it.
--          0 : not in the reserved set (already confirmed/expired, or never reserved).
--
-- Confirm removes only if expiry > now; expire_due removes only if expiry <= now.
-- Both run atomically in Redis, so for any member exactly one of them can win.
-- There's no lock and no retry loop.
local expiry = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expiry then
    return 0
end
if tonumber(expiry) <= tonumber(ARGV[2]) then
    return -1
end
redis.call('ZREM', KEYS[1], ARGV[1])
return 1
