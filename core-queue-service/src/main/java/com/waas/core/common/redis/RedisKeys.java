package com.waas.core.common.redis;

import java.util.UUID;

/**
 * Every Redis key the system uses, in one place (ARCHITECTURE.md §11).
 *
 * <p>The waitlist id is wrapped in {@code {...}}: a Redis Cluster <em>hash tag</em>. Only the
 * part inside the braces is hashed, so all keys of one waitlist land on the same cluster slot.
 * Our Lua scripts touch the queue and reserved keys together, and a multi-key script only
 * works if its keys share a slot. On a single Redis node the braces cost nothing, so the
 * naming is cluster-ready from day one.
 */
public final class RedisKeys {

    private RedisKeys() {}

    /** WAITING entries. Sorted set, score = queue_score, member = waitlist_entry.id. */
    public static String queue(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:queue";
    }

    /** RESERVED entries. Sorted set, score = reservation expiry (epoch ms), member = entry id. */
    public static String reserved(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:reserved";
    }

    /** Pub/Sub channel for queue events consumed by the WebSocket gateway. */
    public static String events(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:events";
    }

    /** Counters for the dashboard (confirmed, expired). Derived data: losing it loses only stats. */
    public static String stats(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:stats";
    }

    /** Short-lived lock so only one Core instance reconciles a given waitlist at a time. */
    public static String reconcileLock(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:reconcile-lock";
    }

    /** Fixed-window counter of referrals credited to one referrer (fraud velocity check). */
    public static String referralVelocity(UUID waitlistId, UUID referrerId) {
        return "waitlist:{" + waitlistId + "}:referral-velocity:" + referrerId;
    }

    /** Staging key a rebuild fills before swapping it in (same hash tag as the live key). */
    public static String staging(String liveKey) {
        return liveKey + ":rebuild";
    }

    /** Short-lived lock so only one Core instance rebuilds a given waitlist. */
    public static String rebuildLock(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:rebuild-lock";
    }
}
