package com.waas.core.queue;

import java.time.Instant;
import java.util.UUID;

/** One {@code waitlist_entry} row. Its id is also the entry's member in the Redis sorted sets. */
public record QueueEntry(
        UUID id,
        UUID waitlistId,
        UUID userId,
        UUID groupId,
        EntryState state,
        long joinSequence,
        double queueScore,
        Instant reservationExpiresAt) {}
