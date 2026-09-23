package com.waas.core.referral;

import com.waas.core.common.events.QueueEvent;
import com.waas.core.common.events.QueueEventPublisher;
import com.waas.core.common.events.QueueEventType;
import com.waas.core.queue.EntryJoined;
import com.waas.core.queue.EntryRepository;
import com.waas.core.queue.EntryState;
import com.waas.core.queue.QueueEntry;
import com.waas.core.referral.ReferralRedis.Bump;
import com.waas.core.referral.ReferralRepository.Activity;
import com.waas.core.referral.ReferralRepository.Ledger;
import com.waas.core.tenant.Waitlist;
import com.waas.core.tenant.WaitlistService;
import com.waas.core.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Referral credits: earn, apply, bank, spend (ARCHITECTURE.md §5 Decision 2, §21.3, §21.4).
 *
 * <p><b>One referral, in order:</b>
 * <ol>
 *   <li><b>Fraud check</b>: velocity limit per referrer per waitlist. Over the limit, the
 *       referral is still <em>recorded</em> (REJECTED + reason) for the audit trail, but earns nothing.</li>
 *   <li><b>Record</b>: {@code INSERT … ON CONFLICT DO NOTHING}. The unique (waitlist, referrer,
 *       referee) row is the idempotency key, so the same referral can never pay out twice.</li>
 *   <li><b>Decide</b>: {@code bump.lua} checks "is the referrer WAITING?" and applies the move
 *       in one atomic step. That check-then-act is the real race (§21.4), and it lives in Lua.</li>
 *   <li><b>Ledger</b>: earned N, applied = spots actually gained, banked = N − applied.</li>
 * </ol>
 *
 * <p>Runs on the joiner's request thread as an {@link EntryJoined} listener. Any failure here is
 * logged, not propagated: the referee's join already succeeded and must not fail because of
 * the referrer's bookkeeping.
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    static final String STATUS_CREDITED = "CREDITED";
    static final String STATUS_REJECTED = "REJECTED";
    static final String REASON_VELOCITY = "VELOCITY_LIMIT";
    static final String REASON_UNKNOWN_REFERRER = "UNKNOWN_REFERRER";

    private final WaitlistService waitlists;
    private final EntryRepository entries;
    private final UserRepository users;
    private final ReferralRepository referrals;
    private final ReferralRedis redis;
    private final QueueEventPublisher events;
    private final Clock clock;
    private final int velocityLimit;
    private final Duration velocityWindow;

    public ReferralService(WaitlistService waitlists, EntryRepository entries, UserRepository users,
                           ReferralRepository referrals, ReferralRedis redis, QueueEventPublisher events, Clock clock,
                           @Value("${waas.referral.velocity-limit:5}") int velocityLimit,
                           @Value("${waas.referral.velocity-window:60s}") Duration velocityWindow) {
        this.waitlists = waitlists;
        this.entries = entries;
        this.users = users;
        this.referrals = referrals;
        this.redis = redis;
        this.events = events;
        this.clock = clock;
        this.velocityLimit = velocityLimit;
        this.velocityWindow = velocityWindow;
    }

    @EventListener
    public void onJoined(EntryJoined joined) {
        try {
            spendBankedCredits(joined.waitlistId(), joined.entryId(), joined.userId());
        } catch (RuntimeException e) {
            log.warn("Spending banked credits failed for user {} on {}", joined.userId(), joined.waitlistId(), e);
        }
        if (joined.referred()) {
            try {
                creditReferral(joined.waitlistId(), joined.referrerId(), joined.userId());
            } catch (RuntimeException e) {
                log.warn("Referral credit failed: {} -> {} on {}", joined.referrerId(), joined.userId(), joined.waitlistId(), e);
            }
        }
    }

    /** Result of crediting one referral, for tests and logs. */
    public record Credit(String status, long earned, long applied, long banked) {}

    Credit creditReferral(UUID waitlistId, UUID referrerId, UUID refereeId) {
        if (referrerId.equals(refereeId)) {
            return new Credit("IGNORED_SELF", 0, 0, 0); // also a DB CHECK, but never worth a round trip
        }
        if (users.findById(referrerId).isEmpty()) {
            return new Credit(REASON_UNKNOWN_REFERRER, 0, 0, 0); // a forged ?ref= must not break the join
        }
        if (redis.countInWindow(waitlistId, referrerId, velocityWindow) > velocityLimit) {
            referrals.insert(waitlistId, referrerId, refereeId, STATUS_REJECTED, REASON_VELOCITY);
            log.info("Referral velocity limit hit: referrer {} on waitlist {}", referrerId, waitlistId);
            return new Credit(STATUS_REJECTED, 0, 0, 0);
        }
        if (!referrals.insert(waitlistId, referrerId, refereeId, STATUS_CREDITED, null)) {
            return new Credit("DUPLICATE", 0, 0, 0);
        }

        Waitlist w = waitlists.require(waitlistId);
        int spots = w.bumpAmount();
        long moved = 0;
        Optional<QueueEntry> entry = entries.findActive(waitlistId, referrerId)
                .filter(e -> e.state() == EntryState.WAITING);
        if (entry.isPresent()) {
            moved = applyBump(waitlistId, entry.get(), spots);
        }
        referrals.recordEarned(waitlistId, referrerId, spots, moved, spots - moved);
        return new Credit(STATUS_CREDITED, spots, moved, spots - moved);
    }

    /**
     * A user who banked credits (they were at the head, reserved, or not in the queue when their
     * referrals landed) gets them applied the moment they're back in the queue.
     */
    void spendBankedCredits(UUID waitlistId, UUID entryId, UUID userId) {
        int pending = referrals.pendingCredits(waitlistId, userId);
        if (pending == 0) {
            return;
        }
        entries.findById(waitlistId, entryId)
                .filter(e -> e.state() == EntryState.WAITING)
                .ifPresent(e -> {
                    Bump b = redis.bump(waitlistId, e.id(), pending);
                    if (b.applied() && b.moved() > 0) {
                        referrals.updateScore(e.id(), b.score(), b.moved());
                        referrals.spendPending(waitlistId, userId, b.moved());
                        events.publish(new QueueEvent(QueueEventType.BUMPED, waitlistId, e.id(), userId, clock.instant()));
                    }
                });
    }

    /** Redis decides (atomic rank move), then Postgres records the new score. Returns spots gained. */
    private long applyBump(UUID waitlistId, QueueEntry entry, int spots) {
        Bump b = redis.bump(waitlistId, entry.id(), spots);
        if (!b.applied() || b.moved() == 0) {
            return 0;
        }
        // If this write is lost, Redis keeps the new order but a rebuild would restore the old
        // score: a bounded, documented gap. Closing it fully needs an outbox (§12.3).
        referrals.updateScore(entry.id(), b.score(), b.moved());
        events.publish(new QueueEvent(QueueEventType.BUMPED, waitlistId, entry.id(), entry.userId(), clock.instant()));
        return b.moved();
    }

    public Ledger ledger(UUID waitlistId, UUID userId) {
        waitlists.require(waitlistId);
        return referrals.ledger(waitlistId, userId);
    }

    public List<Activity> recent(UUID waitlistId, int limit) {
        waitlists.require(waitlistId);
        return referrals.recent(waitlistId, Math.max(1, Math.min(limit, 100)));
    }
}
