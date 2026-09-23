package com.waas.core.queue;

import java.util.UUID;

/**
 * In-process domain event: a new entry just went live in the queue.
 *
 * <p>This is how dependencies are kept pointing one way. Reservation depends on Queue, never
 * the reverse, yet a join must trigger promotion. Queue announces; Reservation listens.
 * It's delivered synchronously on the joining thread (plain Spring {@code @EventListener}), so
 * it adds no async hop or message loss. It isn't the Redis Pub/Sub event, which is for the
 * gateway.
 *
 * <p>Listeners: Reservation promotes into a free slot; Referral spends the joiner's banked credits
 * and credits the referrer ({@code referrerId}, null for a plain join).
 */
public record EntryJoined(UUID waitlistId, UUID entryId, UUID userId, UUID referrerId) {

    /** @return true if the join came through someone's referral link. */
    public boolean referred() {
        return referrerId != null;
    }
}
