package com.waas.core.reservation;

import com.waas.core.queue.EntryState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The reservation transitions on {@code waitlist_entry}. Every one is conditional on the
 * expected current state. Redis has already made the decision atomically; these statements
 * record it, and a stale or duplicate record attempt changes zero rows instead of corrupting state.
 */
@Repository
class ReservationRepository {

    private final JdbcClient jdbc;

    ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** WAITING → RESERVED for a batch promoted together (same window). */
    int markReserved(Collection<UUID> ids, Instant reservedAt, Instant expiresAt) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE waitlist_entry
                           SET state = 'RESERVED', reserved_at = :reservedAt,
                               reservation_expires_at = :expiresAt, updated_at = NOW()
                         WHERE id IN (:ids) AND state = 'WAITING'
                        """)
                .param("ids", ids)
                .param("reservedAt", utc(reservedAt))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /** RESERVED → CONFIRMED. */
    boolean markConfirmed(UUID id, Instant at) {
        return jdbc.sql("""
                        UPDATE waitlist_entry
                           SET state = 'CONFIRMED', confirmed_at = :at, updated_at = NOW()
                         WHERE id = :id AND state = 'RESERVED'
                        """)
                .param("id", id)
                .param("at", utc(at))
                .update() == 1;
    }

    /** RESERVED → EXPIRED. */
    int markExpired(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        UPDATE waitlist_entry
                           SET state = 'EXPIRED', updated_at = NOW()
                         WHERE id IN (:ids) AND state = 'RESERVED'
                        """)
                .param("ids", ids)
                .update();
    }

    /** RESERVED → CANCELLED (the holder declines). */
    boolean markDeclined(UUID id, Instant at) {
        return jdbc.sql("""
                        UPDATE waitlist_entry
                           SET state = 'CANCELLED', cancelled_at = :at, updated_at = NOW()
                         WHERE id = :id AND state = 'RESERVED'
                        """)
                .param("id", id)
                .param("at", utc(at))
                .update() == 1;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    record LiveRow(UUID id, EntryState state, double queueScore, Instant expiresAt, Instant updatedAt) {}

    /** Reconciliation input: every WAITING / RESERVED row of the waitlist. */
    List<LiveRow> findLive(UUID waitlistId) {
        return jdbc.sql("""
                        SELECT id, state, queue_score, reservation_expires_at, updated_at
                          FROM waitlist_entry
                         WHERE waitlist_id = :waitlistId AND state IN ('WAITING', 'RESERVED')
                        """)
                .param("waitlistId", waitlistId)
                .query((rs, i) -> {
                    OffsetDateTime exp = rs.getObject("reservation_expires_at", OffsetDateTime.class);
                    return new LiveRow(
                            rs.getObject("id", UUID.class),
                            EntryState.valueOf(rs.getString("state")),
                            rs.getDouble("queue_score"),
                            exp == null ? null : exp.toInstant(),
                            rs.getObject("updated_at", OffsetDateTime.class).toInstant());
                })
                .list();
    }

    /** Current state of each id; ids with no row are absent from the map. */
    Map<UUID, EntryState> findStates(Collection<UUID> ids) {
        Map<UUID, EntryState> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, state FROM waitlist_entry WHERE id IN (:ids)")
                .param("ids", ids)
                .query(rs -> {
                    out.put(rs.getObject("id", UUID.class), EntryState.valueOf(rs.getString("state")));
                });
        return out;
    }
}
