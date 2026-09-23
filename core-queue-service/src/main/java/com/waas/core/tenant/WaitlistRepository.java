package com.waas.core.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WaitlistRepository {

    private static final String COLUMNS = """
            id, tenant_id, name, description, group_policy, serving_capacity,
            reservation_window_seconds, bump_amount, max_capacity, is_active
            """;

    private final JdbcClient jdbc;

    public WaitlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Waitlist> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM waitlist WHERE id = :id")
                .param("id", id)
                .query(WaitlistRepository::map)
                .optional();
    }

    public List<Waitlist> findAllActive() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM waitlist WHERE is_active ORDER BY created_at, name")
                .query(WaitlistRepository::map)
                .list();
    }

    /** Partial update; NULL arguments keep the current value. */
    public Optional<Waitlist> updateConfig(UUID id, Integer servingCapacity, Integer reservationWindowSeconds) {
        return jdbc.sql("""
                        UPDATE waitlist
                           SET serving_capacity = COALESCE(:capacity, serving_capacity),
                               reservation_window_seconds = COALESCE(:window, reservation_window_seconds),
                               updated_at = NOW()
                         WHERE id = :id
                        RETURNING\s""" + COLUMNS)
                .param("id", id)
                .param("capacity", servingCapacity, java.sql.Types.INTEGER)
                .param("window", reservationWindowSeconds, java.sql.Types.INTEGER)
                .query(WaitlistRepository::map)
                .optional();
    }

    public Optional<UUID> tenantForApiKey(String apiKey) {
        return jdbc.sql("SELECT id FROM tenant WHERE api_key = :k").param("k", apiKey).query(UUID.class).optional();
    }

    private static Waitlist map(ResultSet rs, int row) throws SQLException {
        return new Waitlist(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("group_policy"),
                rs.getInt("serving_capacity"),
                rs.getInt("reservation_window_seconds"),
                rs.getInt("bump_amount"),
                rs.getObject("max_capacity", Integer.class),
                rs.getBoolean("is_active"));
    }
}
