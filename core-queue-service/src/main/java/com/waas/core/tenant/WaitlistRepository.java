package com.waas.core.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WaitlistRepository {

    private static final String COLUMNS = """
            id, tenant_id, name, description, group_policy, serving_capacity,
            reservation_window_seconds, bump_amount, max_capacity, is_active, created_at
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

    /** Active, owned by the tenant, name contains the search text (case-insensitive). */
    private static final String TENANT_FILTER = "tenant_id = :tenant AND is_active AND name ILIKE :like";

    /**
     * One page of a tenant's waitlists, newest first. {@code idx_waitlist_tenant} keeps this a
     * per-tenant lookup however many waitlists other tenants have.
     */
    public List<Waitlist> page(UUID tenantId, String nameContains, int limit, int offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM waitlist WHERE " + TENANT_FILTER
                        + " ORDER BY created_at DESC, name LIMIT :limit OFFSET :offset")
                .param("tenant", tenantId)
                .param("like", like(nameContains))
                .param("limit", limit)
                .param("offset", offset)
                .query(WaitlistRepository::map)
                .list();
    }

    public long count(UUID tenantId, String nameContains) {
        return jdbc.sql("SELECT COUNT(*) FROM waitlist WHERE " + TENANT_FILTER)
                .param("tenant", tenantId)
                .param("like", like(nameContains))
                .query(Long.class)
                .single();
    }

    /** "air" → "%air%". The user's own % and _ are escaped (backslash is LIKE's default escape). */
    static String like(String text) {
        String t = text == null ? "" : text.strip();
        return "%" + t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    public Waitlist insert(UUID tenantId, String name, String description, String groupPolicy,
                           int servingCapacity, int reservationWindowSeconds, int bumpAmount) {
        return jdbc.sql("""
                        INSERT INTO waitlist (tenant_id, name, description, group_policy,
                                              serving_capacity, reservation_window_seconds, bump_amount)
                        VALUES (:tenant, :name, :description, :policy, :capacity, :window, :bump)
                        RETURNING\s""" + COLUMNS)
                .param("tenant", tenantId)
                .param("name", name)
                .param("description", description)
                .param("policy", groupPolicy)
                .param("capacity", servingCapacity)
                .param("window", reservationWindowSeconds)
                .param("bump", bumpAmount)
                .query(WaitlistRepository::map)
                .single();
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

    public String tenantName(UUID tenantId) {
        return jdbc.sql("SELECT name FROM tenant WHERE id = :id").param("id", tenantId).query(String.class).single();
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
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
