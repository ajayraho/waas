package com.waas.core.queue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All SQL for {@code waitlist_entry}. Every state change is a <em>conditional</em> UPDATE
 * ({@code WHERE state = <expected>}), so a transition that lost a race changes zero rows
 * instead of overwriting someone else's transition.
 */
@Repository
public class EntryRepository {

    private static final String COLUMNS = """
            id, waitlist_id, user_id, group_id, state, join_sequence, queue_score, reservation_expires_at
            """;

    private final JdbcClient jdbc;

    public EntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert a WAITING entry. One statement takes one {@code nextval()} and uses it for both
     * {@code join_sequence} and the initial {@code queue_score}; the app reads both back with
     * RETURNING. This is the "Postgres first" half of the join (§21.5).
     *
     * @throws org.springframework.dao.DuplicateKeyException if the user already has an active
     *     entry on this waitlist (partial unique index {@code idx_waitlist_entry_active})
     */
    public QueueEntry insertWaiting(UUID waitlistId, UUID userId, UUID groupId) {
        return jdbc.sql("""
                        INSERT INTO waitlist_entry (waitlist_id, user_id, group_id, join_sequence, queue_score)
                        SELECT :waitlistId, :userId, :groupId, s.v, s.v
                        FROM (SELECT nextval('waitlist_join_seq') AS v) s
                        RETURNING\s""" + COLUMNS)
                .param("waitlistId", waitlistId)
                .param("userId", userId)
                .param("groupId", groupId, java.sql.Types.OTHER)
                .query(EntryRepository::map)
                .single();
    }

    /** The user's non-terminal entry on this waitlist, if any. */
    public Optional<QueueEntry> findActive(UUID waitlistId, UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM waitlist_entry
                        WHERE waitlist_id = :waitlistId AND user_id = :userId
                          AND state NOT IN ('CANCELLED', 'EXPIRED')
                        """)
                .param("waitlistId", waitlistId)
                .param("userId", userId)
                .query(EntryRepository::map)
                .optional();
    }

    public Optional<QueueEntry> findById(UUID waitlistId, UUID entryId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM waitlist_entry WHERE id = :id AND waitlist_id = :waitlistId")
                .param("id", entryId)
                .param("waitlistId", waitlistId)
                .query(EntryRepository::map)
                .optional();
    }

    /** The live entry of a STRICT group (the one shared entry). */
    public Optional<QueueEntry> findByGroup(UUID waitlistId, UUID groupId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM waitlist_entry
                        WHERE waitlist_id = :waitlistId AND group_id = :groupId
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("waitlistId", waitlistId)
                .param("groupId", groupId)
                .query(EntryRepository::map)
                .optional();
    }

    /** WAITING → CANCELLED. Returns false if the entry was no longer WAITING. */
    public boolean cancelIfWaiting(UUID entryId) {
        return jdbc.sql("""
                        UPDATE waitlist_entry
                           SET state = 'CANCELLED', cancelled_at = NOW(), updated_at = NOW()
                         WHERE id = :id AND state = 'WAITING'
                        """)
                .param("id", entryId)
                .update() == 1;
    }

    /** Rebuild source (§21.6): every entry that must live in Redis. Uses (waitlist_id, state). */
    public List<QueueEntry> findLive(UUID waitlistId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM waitlist_entry
                        WHERE waitlist_id = :waitlistId AND state IN ('WAITING', 'RESERVED')
                        """)
                .param("waitlistId", waitlistId)
                .query(EntryRepository::map)
                .list();
    }

    /** groupSize/groupConfirmed are 0 for entries that aren't part of a group. */
    /**
     * @param boost     queue places this entry actually gained from referrals (total_bumps_applied)
     * @param referrals friends this user brought in on this waitlist that earned credit
     */
    public record EntryOwner(UUID entryId, UUID userId, String userName, UUID groupId, int groupSize, int groupConfirmed,
                             Instant joinedAt, int boost, int referrals) {}

    /** Display data for a page of entries. Called with at most one page (≤ 200 ids). */
    public Map<UUID, EntryOwner> findOwners(Collection<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        SELECT e.id, e.user_id, u.name, e.group_id, e.created_at, e.total_bumps_applied,
                               (SELECT COUNT(*) FROM group_member gm WHERE gm.group_id = e.group_id) AS group_size,
                               (SELECT COUNT(*) FROM group_member gm WHERE gm.group_id = e.group_id AND gm.has_confirmed) AS group_confirmed,
                               (SELECT COUNT(*) FROM referral r
                                 WHERE r.waitlist_id = e.waitlist_id AND r.referrer_id = e.user_id
                                   AND r.status = 'CREDITED') AS referrals
                          FROM waitlist_entry e JOIN app_user u ON u.id = e.user_id
                         WHERE e.id IN (:ids)
                        """)
                .param("ids", entryIds)
                .query((rs, i) -> new EntryOwner(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
                        rs.getObject("group_id", UUID.class), rs.getInt("group_size"), rs.getInt("group_confirmed"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("total_bumps_applied"), rs.getInt("referrals")))
                .list()
                .stream()
                .collect(Collectors.toMap(EntryOwner::entryId, Function.identity()));
    }

    private static QueueEntry map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime expires = rs.getObject("reservation_expires_at", OffsetDateTime.class);
        return new QueueEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("waitlist_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("group_id", UUID.class),
                EntryState.valueOf(rs.getString("state")),
                rs.getLong("join_sequence"),
                rs.getDouble("queue_score"),
                expires == null ? null : expires.toInstant());
    }
}
