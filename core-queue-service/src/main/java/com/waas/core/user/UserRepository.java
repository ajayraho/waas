package com.waas.core.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AppUser create(String email, String name) {
        return create(email, name, "guest");
    }

    public AppUser create(String email, String name, String passwordHash) {
        return jdbc.sql("""
                        INSERT INTO app_user (email, name, password_hash)
                        VALUES (:email, :name, :hash)
                        RETURNING id, email, name
                        """)
                .param("email", email)
                .param("name", name)
                .param("hash", passwordHash)
                .query((rs, i) -> new AppUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name")))
                .single();
    }

    public record WithHash(AppUser user, String passwordHash) {}

    public Optional<WithHash> findWithHash(String email) {
        return jdbc.sql("SELECT id, email, name, password_hash FROM app_user WHERE email = :email")
                .param("email", email)
                .query((rs, i) -> new WithHash(
                        new AppUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name")),
                        rs.getString("password_hash")))
                .optional();
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.sql("SELECT id, email, name FROM app_user WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new AppUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name")))
                .optional();
    }
}
