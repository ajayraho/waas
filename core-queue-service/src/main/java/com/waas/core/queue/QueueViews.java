package com.waas.core.queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API response shapes for the queue endpoints. */
public final class QueueViews {

    private QueueViews() {}

    /**
     * Where one entry stands.
     *
     * @param position   1-based place among WAITING entries (null unless WAITING)
     * @param queueSize  WAITING entries in total (null unless WAITING)
     * @param expiresAt  end of the checkout window (null unless RESERVED)
     */
    public record EntryPosition(
            UUID entryId,
            UUID waitlistId,
            EntryState state,
            Long position,
            Long queueSize,
            Instant expiresAt) {}

    /** Join result. {@code created} = false means an idempotent repeat of an earlier join. */
    public record JoinResponse(boolean created, UUID entryId, UUID userId, EntryPosition position) {}

    /**
     * @param joinedAt  when the entry was created
     * @param boost     places gained from referrals
     * @param referrals friends referred (credited) on this waitlist
     */
    public record WaitingRow(long position, UUID entryId, UUID userId, String name, double score,
                             UUID groupId, int groupSize, Instant joinedAt, int boost, int referrals) {}

    public record ReservedRow(UUID entryId, UUID userId, String name, Instant expiresAt,
                              UUID groupId, int groupSize, int groupConfirmed) {}

    /** The dashboard's view of one waitlist: reserved slots + the head of the queue. */
    public record QueueSnapshot(
            UUID waitlistId,
            int servingCapacity,
            long queueSize,
            long reservedCount,
            long confirmedCount,
            long expiredCount,
            List<ReservedRow> reserved,
            List<WaitingRow> waiting,
            Instant at) {}
}
