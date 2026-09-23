package com.waas.core.referral;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReferralRepository {

    private final JdbcClient jdbc;

    ReferralRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record a referral. The UNIQUE (waitlist, referrer, referee) constraint is the idempotency
     * key: returns false when this exact referral already exists, so it can never be credited twice.
     */
    boolean insert(UUID waitlistId, UUID referrerId, UUID refereeId, String status, String rejectionReason) {
        return jdbc.sql("""
                        INSERT INTO referral (waitlist_id, referrer_id, referee_id, status, rejection_reason)
                        VALUES (:w, :referrer, :referee, :status, :reason)
                        ON CONFLICT (waitlist_id, referrer_id, referee_id) DO NOTHING
                        """)
                .param("w", waitlistId)
                .param("referrer", referrerId)
                .param("referee", refereeId)
                .param("status", status)
                .param("reason", rejectionReason, java.sql.Types.VARCHAR)
                .update() == 1;
    }

    /** Persist the bumped score. Conditional on WAITING, like every transition. */
    boolean updateScore(UUID entryId, double score, long moved) {
        return jdbc.sql("""
                        UPDATE waitlist_entry
                           SET queue_score = :score,
                               total_bumps_applied = total_bumps_applied + :moved,
                               updated_at = NOW()
                         WHERE id = :id AND state = 'WAITING'
                        """)
                .param("id", entryId)
                .param("score", score)
                .param("moved", moved)
                .update() == 1;
    }

    /**
     * Ledger upsert: one row per (waitlist, user), created on first credit. All three counters
     * move with atomic {@code col = col + n}: no read-modify-write, so no lost update
     * (the §10 race, solved without a version column).
     */
    void recordEarned(UUID waitlistId, UUID userId, long earned, long applied, long banked) {
        jdbc.sql("""
                        INSERT INTO referral_credit (waitlist_id, user_id, total_credits_earned, total_credits_applied, pending_credits)
                        VALUES (:w, :u, :earned, :applied, :banked)
                        ON CONFLICT (waitlist_id, user_id) DO UPDATE SET
                            total_credits_earned  = referral_credit.total_credits_earned  + EXCLUDED.total_credits_earned,
                            total_credits_applied = referral_credit.total_credits_applied + EXCLUDED.total_credits_applied,
                            pending_credits       = referral_credit.pending_credits       + EXCLUDED.pending_credits,
                            updated_at = NOW()
                        """)
                .param("w", waitlistId)
                .param("u", userId)
                .param("earned", earned)
                .param("applied", applied)
                .param("banked", banked)
                .update();
    }

    int pendingCredits(UUID waitlistId, UUID userId) {
        return jdbc.sql("SELECT pending_credits FROM referral_credit WHERE waitlist_id = :w AND user_id = :u")
                .param("w", waitlistId)
                .param("u", userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** Spend banked credits. Conditional ({@code pending >= n}), so a double spend changes nothing. */
    boolean spendPending(UUID waitlistId, UUID userId, long n) {
        return jdbc.sql("""
                        UPDATE referral_credit
                           SET pending_credits = pending_credits - :n,
                               total_credits_applied = total_credits_applied + :n,
                               updated_at = NOW()
                         WHERE waitlist_id = :w AND user_id = :u AND pending_credits >= :n
                        """)
                .param("w", waitlistId)
                .param("u", userId)
                .param("n", n)
                .update() == 1;
    }

    public record Ledger(long earned, long applied, long pending, long credited, long rejected) {}

    Ledger ledger(UUID waitlistId, UUID userId) {
        Optional<long[]> credit = jdbc.sql("""
                        SELECT total_credits_earned, total_credits_applied, pending_credits
                          FROM referral_credit WHERE waitlist_id = :w AND user_id = :u
                        """)
                .param("w", waitlistId)
                .param("u", userId)
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)})
                .optional();
        long[] counts = jdbc.sql("""
                        SELECT COUNT(*) FILTER (WHERE status = 'CREDITED'),
                               COUNT(*) FILTER (WHERE status = 'REJECTED')
                          FROM referral WHERE waitlist_id = :w AND referrer_id = :u
                        """)
                .param("w", waitlistId)
                .param("u", userId)
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
        long[] c = credit.orElse(new long[3]);
        return new Ledger(c[0], c[1], c[2], counts[0], counts[1]);
    }

    public record Activity(UUID referrerId, String referrerName, UUID refereeId, String refereeName,
                           String status, String rejectionReason, Instant at) {}

    List<Activity> recent(UUID waitlistId, int limit) {
        return jdbc.sql("""
                        SELECT r.referrer_id, a.name AS referrer_name, r.referee_id, b.name AS referee_name,
                               r.status, r.rejection_reason, r.created_at
                          FROM referral r
                          JOIN app_user a ON a.id = r.referrer_id
                          JOIN app_user b ON b.id = r.referee_id
                         WHERE r.waitlist_id = :w
                         ORDER BY r.created_at DESC
                         LIMIT :limit
                        """)
                .param("w", waitlistId)
                .param("limit", limit)
                .query((rs, i) -> new Activity(
                        rs.getObject("referrer_id", UUID.class), rs.getString("referrer_name"),
                        rs.getObject("referee_id", UUID.class), rs.getString("referee_name"),
                        rs.getString("status"), rs.getString("rejection_reason"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
