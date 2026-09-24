package com.waas.core.queue;

import com.waas.core.common.error.ApiException;
import com.waas.core.common.events.QueueEvent;
import com.waas.core.common.events.QueueEventPublisher;
import com.waas.core.common.events.QueueEventType;
import com.waas.core.queue.QueueRedis.Absent;
import com.waas.core.queue.QueueRedis.Reserved;
import com.waas.core.queue.QueueRedis.Scored;
import com.waas.core.queue.QueueRedis.Waiting;
import com.waas.core.queue.QueueViews.EntryPosition;
import com.waas.core.queue.QueueViews.JoinResponse;
import com.waas.core.queue.QueueViews.QueueSnapshot;
import com.waas.core.queue.QueueViews.ReservedRow;
import com.waas.core.queue.QueueViews.WaitingRow;
import com.waas.core.tenant.Waitlist;
import com.waas.core.tenant.WaitlistService;
import com.waas.core.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Join, position, cancel, snapshot.
 *
 * <p>Write ordering is always <b>Postgres first, then Redis</b> (§21.5). If the Redis step fails,
 * a durable row exists without a live queue member. The client's retry repairs it (join is
 * idempotent, and ZADD NX re-adds the member) and so does the startup rebuild. The reverse
 * order could leave a queue member with no durable record at all.
 *
 * <p>No {@code @Transactional}: each write is a single SQL statement, and a DB transaction
 * can't include the Redis write anyway. Pretending otherwise would be misleading.
 */
@Service
public class QueueService {

    static final int MAX_SNAPSHOT = 200;

    private final WaitlistService waitlists;
    private final UserRepository users;
    private final EntryRepository entries;
    private final QueueRedis queue;
    private final QueueEventPublisher events;
    private final ApplicationEventPublisher domainEvents;
    private final Clock clock;

    public QueueService(WaitlistService waitlists, UserRepository users, EntryRepository entries,
                        QueueRedis queue, QueueEventPublisher events, ApplicationEventPublisher domainEvents,
                        Clock clock) {
        this.waitlists = waitlists;
        this.users = users;
        this.entries = entries;
        this.queue = queue;
        this.events = events;
        this.domainEvents = domainEvents;
        this.clock = clock;
    }

    /**
     * Join a waitlist. Idempotent per (user, waitlist): repeating the call returns the existing
     * entry instead of failing. That makes client retries safe without an idempotency-key store,
     * because the partial unique index is the dedup key.
     */
    public JoinResponse join(UUID waitlistId, UUID userId) {
        return join(waitlistId, userId, null);
    }

    /**
     * @param referrerId who referred this user (the {@code ?ref=} of a referral link), or null.
     *     Only a <em>new</em> entry announces the referral. An idempotent repeat doesn't, so
     *     retrying a join can't earn the referrer a second credit.
     */
    public JoinResponse join(UUID waitlistId, UUID userId, UUID referrerId) {
        return join(waitlistId, userId, referrerId, null);
    }

    /** Used by the group module: same join, with the entry tagged to a group. */
    public JoinResponse joinAsGroup(UUID waitlistId, UUID userId, UUID groupId) {
        return join(waitlistId, userId, null, groupId);
    }

    private JoinResponse join(UUID waitlistId, UUID userId, UUID referrerId, UUID groupId) {
        Waitlist waitlist = waitlists.require(waitlistId);
        if (!waitlist.active()) {
            throw ApiException.conflict("WAITLIST_CLOSED", "Waitlist is not accepting joins");
        }

        var existing = entries.findActive(waitlistId, userId);
        if (existing.isPresent()) {
            return new JoinResponse(false, existing.get().id(), userId, repairAndLocate(existing.get()));
        }

        // Soft cap: ZCARD-then-insert isn't atomic, so concurrent joins can overshoot
        // max_capacity by at most the number of in-flight joins. Accepted: a hard cap would need
        // the Postgres insert and Redis add to be one atomic unit, and they can't be.
        if (waitlist.maxCapacity() != null && queue.queueSize(waitlistId) >= waitlist.maxCapacity()) {
            throw ApiException.conflict("WAITLIST_FULL", "Waitlist is full");
        }

        QueueEntry entry;
        try {
            entry = entries.insertWaiting(waitlistId, userId, groupId);                   // 1. Postgres
        } catch (DuplicateKeyException race) {
            // The same user joined concurrently (double-click, retry storm) and the other
            // request won the unique index. Answer with the winner's entry.
            QueueEntry winner = entries.findActive(waitlistId, userId).orElseThrow(() -> race);
            return new JoinResponse(false, winner.id(), userId, repairAndLocate(winner));
        } catch (DataIntegrityViolationException unknownUser) {
            // No separate "does the user exist?" query: the user_id foreign key checks it.
            throw ApiException.notFound("USER_NOT_FOUND", "No user " + userId);
        }

        queue.join(waitlistId, entry.id(), entry.queueScore());                    // 2. Redis
        events.publish(new QueueEvent(QueueEventType.JOINED, waitlistId, entry.id(), userId, clock.instant()));

        // 3. A join may land in a queue with a free serving slot. The Reservation module owns
        //    promotion, and queue must not depend on it (§22 package rules), so we announce the
        //    join as an in-process event. The listener runs synchronously, so by the time
        //    locate() runs below, the entry may already be RESERVED (or bumped by spent credits),
        //    and the response says so.
        domainEvents.publishEvent(new EntryJoined(waitlistId, entry.id(), userId, referrerId));

        return new JoinResponse(true, entry.id(), userId, position(waitlistId, entry.id()));
    }

    /** Current position, from Redis. Postgres is read only when the entry isn't live (§21.8). */
    public EntryPosition position(UUID waitlistId, UUID entryId) {
        waitlists.require(waitlistId);
        return switch (queue.locate(waitlistId, entryId)) {
            case Waiting w -> new EntryPosition(entryId, waitlistId, EntryState.WAITING, w.rank() + 1, w.queueSize(), null);
            case Reserved r -> new EntryPosition(entryId, waitlistId, EntryState.RESERVED, null, null, r.expiresAt());
            case Absent a -> fromPostgres(waitlistId, entryId);
        };
    }

    /**
     * WAITING → CANCELLED. Postgres decides (conditional UPDATE), then Redis follows.
     * RESERVED → CANCELLED frees a serving slot, so it is the Reservation module's {@code decline}.
     */
    public void cancel(UUID waitlistId, UUID entryId) {
        cancel(waitlistId, entryId, null);
    }

    /** @param userId if non-null, must own the entry (else 404, like confirm). */
    public void cancel(UUID waitlistId, UUID entryId, UUID userId) {
        QueueEntry entry = entries.findById(waitlistId, entryId)
                .filter(e -> userId == null || e.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("ENTRY_NOT_FOUND", "No entry " + entryId));
        if (entry.state() == EntryState.CANCELLED) {
            return; // idempotent
        }
        if (entry.state() != EntryState.WAITING || !entries.cancelIfWaiting(entryId)) {
            throw ApiException.conflict("NOT_WAITING", "Only a WAITING entry can be cancelled here");
        }
        queue.remove(waitlistId, entryId);
        events.publish(new QueueEvent(QueueEventType.CANCELLED, waitlistId, entryId, entry.userId(), clock.instant()));
    }

    /** Dashboard view: every reserved slot plus the first {@code limit} WAITING entries. */
    public QueueSnapshot snapshot(UUID waitlistId, int limit) {
        Waitlist waitlist = waitlists.require(waitlistId);
        int n = Math.max(1, Math.min(limit, MAX_SNAPSHOT));
        QueueRedis.Snapshot s = queue.snapshot(waitlistId, n);

        Map<UUID, EntryRepository.EntryOwner> owners = entries.findOwners(
                Stream.concat(s.waiting().stream(), s.reserved().stream()).map(Scored::entryId).toList());

        List<ReservedRow> reserved = new ArrayList<>();
        for (Scored r : s.reserved()) {
            var o = owners.get(r.entryId());
            reserved.add(new ReservedRow(r.entryId(), o == null ? null : o.userId(), o == null ? "?" : o.userName(),
                    Instant.ofEpochMilli((long) r.score()), o == null ? null : o.groupId(),
                    o == null ? 0 : o.groupSize(), o == null ? 0 : o.groupConfirmed()));
        }
        List<WaitingRow> waiting = new ArrayList<>();
        long position = 1;
        for (Scored w : s.waiting()) {
            var o = owners.get(w.entryId());
            waiting.add(new WaitingRow(position++, w.entryId(), o == null ? null : o.userId(),
                    o == null ? "?" : o.userName(), w.score(), o == null ? null : o.groupId(), o == null ? 0 : o.groupSize(),
                    o == null ? null : o.joinedAt(), o == null ? 0 : o.boost(), o == null ? 0 : o.referrals()));
        }
        return new QueueSnapshot(waitlistId, waitlist.servingCapacity(), s.queueSize(), s.reservedSize(),
                s.confirmed(), s.expired(), reserved, waiting, clock.instant());
    }

    /**
     * An existing active entry is being returned (idempotent join). If it's WAITING but missing
     * from Redis (an earlier ZADD failed), re-add it with its stored score. join.lua uses ZADD NX,
     * so an entry that is present keeps its current score.
     */
    private EntryPosition repairAndLocate(QueueEntry entry) {
        if (entry.state() == EntryState.WAITING) {
            queue.join(entry.waitlistId(), entry.id(), entry.queueScore());
        }
        return position(entry.waitlistId(), entry.id());
    }

    private EntryPosition fromPostgres(UUID waitlistId, UUID entryId) {
        QueueEntry entry = entries.findById(waitlistId, entryId)
                .orElseThrow(() -> ApiException.notFound("ENTRY_NOT_FOUND", "No entry " + entryId));
        // WAITING/RESERVED in Postgres but absent from Redis = the not-yet-repaired gap from a
        // failed Redis write. Report the durable state, but no position: we can't know it.
        return new EntryPosition(entryId, waitlistId, entry.state(), null, null, entry.reservationExpiresAt());
    }
}
