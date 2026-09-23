package com.waas.core.reservation;

import com.waas.core.common.error.ApiException;
import com.waas.core.common.events.QueueEvent;
import com.waas.core.common.events.QueueEventPublisher;
import com.waas.core.common.events.QueueEventType;
import com.waas.core.queue.EntryJoined;
import com.waas.core.queue.EntryRepository;
import com.waas.core.queue.EntryState;
import com.waas.core.queue.QueueEntry;
import com.waas.core.reservation.ReservationRedis.ConfirmOutcome;
import com.waas.core.reservation.ReservationRedis.Promotion;
import com.waas.core.tenant.Waitlist;
import com.waas.core.tenant.WaitlistConfigChanged;
import com.waas.core.tenant.WaitlistService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Serving capacity and the checkout window (ARCHITECTURE.md §21.1, §21.2).
 *
 * <p><b>Write order is Redis first, then Postgres</b>, the opposite of join. That's deliberate.
 * Join creates a record, so the durable store goes first. Here the question is "who gets
 * the slot / did the confirm beat the clock", and that decision is made atomically in Lua. Postgres
 * then records it with conditional UPDATEs. If the Postgres write fails, the {@link Reconciler}
 * rolls it forward. Redis is authoritative for slot decisions, Postgres for existence (§21.6).
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    static final int EXPIRE_BATCH = 100;
    static final String STAT_CONFIRMED = "confirmed";
    static final String STAT_EXPIRED = "expired";

    private final WaitlistService waitlists;
    private final EntryRepository entries;
    private final ReservationRepository reservations;
    private final ReservationRedis redis;
    private final QueueEventPublisher events;
    private final Clock clock;

    public ReservationService(WaitlistService waitlists, EntryRepository entries, ReservationRepository reservations,
                              ReservationRedis redis, QueueEventPublisher events, Clock clock) {
        this.waitlists = waitlists;
        this.entries = entries;
        this.reservations = reservations;
        this.redis = redis;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Fill every free slot from the head of the queue. Idempotent and cheap (one Lua call when
     * nothing is free), so it runs after every slot-freeing event and on every sweeper tick.
     */
    public List<UUID> promote(UUID waitlistId) {
        Waitlist w = waitlists.require(waitlistId);
        Instant now = clock.instant();
        Promotion p = redis.promote(waitlistId, w.servingCapacity(), now, w.reservationWindowSeconds() * 1000L);
        if (!p.entryIds().isEmpty()) {
            reservations.markReserved(p.entryIds(), now, p.expiresAt());
            publish(QueueEventType.RESERVED, waitlistId, p.entryIds());
        }
        return p.entryIds();
    }

    /**
     * The holder confirms inside their window: RESERVED → CONFIRMED, slot freed, next promoted.
     * Idempotent: confirming an already-confirmed entry succeeds quietly.
     */
    public void confirm(UUID waitlistId, UUID entryId, UUID userId) {
        QueueEntry entry = owned(waitlistId, entryId, userId);
        // A STRICT group's entry is confirmed by its members, via POST /groups/{id}/confirm.
        if (entry.groupId() != null && "STRICT".equals(waitlists.require(waitlistId).groupPolicy())) {
            throw ApiException.conflict("GROUP_CONFIRM_REQUIRED", "Every group member has to confirm");
        }
        confirmEntry(entry);
    }

    /** Confirm without the owner check. Called by the group module once all members have confirmed. */
    public void confirmEntry(QueueEntry entry) {
        UUID waitlistId = entry.waitlistId();
        UUID entryId = entry.id();
        if (entry.state() == EntryState.CONFIRMED) {
            return;
        }
        if (entry.state() != EntryState.RESERVED) {
            throw ApiException.conflict("NOT_RESERVED", "Entry is " + entry.state() + ", not RESERVED");
        }
        Instant now = clock.instant();
        ConfirmOutcome outcome = redis.confirm(waitlistId, entryId, now);

        // ABSENT + still inside the window in Postgres = our own earlier attempt already took it
        // out of Redis but failed before recording CONFIRMED. Finish the job (retry = repair).
        boolean inTime = outcome == ConfirmOutcome.CONFIRMED
                || (outcome == ConfirmOutcome.ABSENT && entry.reservationExpiresAt() != null
                    && entry.reservationExpiresAt().isAfter(now));
        if (!inTime) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_EXPIRED", "The checkout window has closed");
        }
        if (reservations.markConfirmed(entryId, now)) {
            redis.incrementStat(waitlistId, STAT_CONFIRMED, 1);
            events.publish(new QueueEvent(QueueEventType.CONFIRMED, waitlistId, entryId, entry.userId(), now));
        }
        promote(waitlistId);
    }

    /** The holder gives up their slot: RESERVED → CANCELLED, next promoted. */
    public void decline(UUID waitlistId, UUID entryId, UUID userId) {
        QueueEntry entry = owned(waitlistId, entryId, userId);
        if (entry.state() == EntryState.CANCELLED) {
            return;
        }
        if (entry.state() != EntryState.RESERVED) {
            throw ApiException.conflict("NOT_RESERVED", "Entry is " + entry.state() + ", not RESERVED");
        }
        Instant now = clock.instant();
        redis.release(waitlistId, entryId);
        if (!reservations.markDeclined(entryId, now)) {
            throw ApiException.conflict("NOT_RESERVED", "The reservation ended before it could be declined");
        }
        events.publish(new QueueEvent(QueueEventType.CANCELLED, waitlistId, entryId, entry.userId(), now));
        promote(waitlistId);
    }

    /**
     * One sweeper step for one waitlist: expire what lapsed, then refill.
     * Safe to run on every Core instance at once: expire_due.lua hands each lapsed entry to
     * exactly one caller, and promote.lua can't overfill.
     */
    public int sweep(UUID waitlistId) {
        List<UUID> lapsed = redis.expireDue(waitlistId, clock.instant(), EXPIRE_BATCH);
        if (!lapsed.isEmpty()) {
            int recorded = reservations.markExpired(lapsed);
            redis.incrementStat(waitlistId, STAT_EXPIRED, recorded);
            publish(QueueEventType.EXPIRED, waitlistId, lapsed);
        }
        promote(waitlistId);
        return lapsed.size();
    }

    /**
     * A join may land while a slot is free: promote immediately, same thread, same request.
     * A failure here must not fail the join, which is already durable and live. The sweeper's
     * next tick (≤ 1 s) promotes anyway, so log and move on.
     */
    @EventListener
    public void onJoined(EntryJoined event) {
        try {
            promote(event.waitlistId());
        } catch (RuntimeException e) {
            log.warn("Promotion after join failed on waitlist {}; the sweeper will retry", event.waitlistId(), e);
        }
    }

    /** A capacity increase frees slots right now. */
    @EventListener
    public void onConfigChanged(WaitlistConfigChanged event) {
        promote(event.waitlistId());
    }

    /** 404 (not 403) when the entry isn't the caller's: don't confirm that it exists. */
    private QueueEntry owned(UUID waitlistId, UUID entryId, UUID userId) {
        return entries.findById(waitlistId, entryId)
                .filter(e -> e.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("ENTRY_NOT_FOUND", "No entry " + entryId));
    }

    private void publish(QueueEventType type, UUID waitlistId, List<UUID> entryIds) {
        Map<UUID, EntryRepository.EntryOwner> owners = entries.findOwners(entryIds);
        Instant now = clock.instant();
        for (UUID id : entryIds) {
            EntryRepository.EntryOwner o = owners.get(id);
            events.publish(new QueueEvent(type, waitlistId, id, o == null ? null : o.userId(), now));
        }
    }
}
