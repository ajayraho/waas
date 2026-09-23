package com.waas.core.group;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class GroupRepository {

    private final JdbcClient jdbc;

    GroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID create(UUID waitlistId, UUID createdBy, List<UUID> members) {
        UUID groupId = jdbc.sql("INSERT INTO waitlist_group (waitlist_id, created_by) VALUES (:w, :u) RETURNING id")
                .param("w", waitlistId)
                .param("u", createdBy)
                .query(UUID.class)
                .single();
        for (UUID member : members) {
            jdbc.sql("INSERT INTO group_member (group_id, user_id) VALUES (:g, :u)")
                    .param("g", groupId)
                    .param("u", member)
                    .update();
        }
        return groupId;
    }

    Optional<UUID> waitlistOf(UUID groupId) {
        return jdbc.sql("SELECT waitlist_id FROM waitlist_group WHERE id = :g")
                .param("g", groupId).query(UUID.class).optional();
    }

    record Member(UUID userId, String name, boolean confirmed) {}

    List<Member> members(UUID groupId) {
        return jdbc.sql("""
                        SELECT gm.user_id, u.name, gm.has_confirmed
                          FROM group_member gm JOIN app_user u ON u.id = gm.user_id
                         WHERE gm.group_id = :g
                         ORDER BY gm.joined_at, u.name
                        """)
                .param("g", groupId)
                .query((rs, i) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("name"),
                        rs.getBoolean("has_confirmed")))
                .list();
    }

    /** Returns false if the user isn't a member (or already confirmed). */
    boolean confirmMember(UUID groupId, UUID userId) {
        return jdbc.sql("""
                        UPDATE group_member SET has_confirmed = TRUE, confirmed_at = NOW()
                         WHERE group_id = :g AND user_id = :u AND NOT has_confirmed
                        """)
                .param("g", groupId).param("u", userId).update() == 1;
    }

    boolean isMember(UUID groupId, UUID userId) {
        return jdbc.sql("SELECT COUNT(*) FROM group_member WHERE group_id = :g AND user_id = :u")
                .param("g", groupId).param("u", userId).query(Integer.class).single() > 0;
    }

    /** Which of these users already have an active entry on this waitlist. */
    List<UUID> alreadyQueued(UUID waitlistId, Collection<UUID> userIds) {
        return jdbc.sql("""
                        SELECT user_id FROM waitlist_entry
                         WHERE waitlist_id = :w AND user_id IN (:ids) AND state NOT IN ('CANCELLED', 'EXPIRED')
                        """)
                .param("w", waitlistId).param("ids", userIds).query(UUID.class).list();
    }
}
