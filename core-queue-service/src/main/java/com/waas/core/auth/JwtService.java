package com.waas.core.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** HS256 tokens. Subject = user id. Stateless: nothing is stored server-side. */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(@Value("${waas.auth.jwt-secret}") String secret,
                      @Value("${waas.auth.token-ttl:12h}") Duration ttl, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)); // needs >= 32 bytes
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(UUID userId, String name) {
        Date now = Date.from(clock.instant());
        return Jwts.builder()
                .subject(userId.toString())
                .claim("name", name)
                .issuedAt(now)
                .expiration(Date.from(clock.instant().plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** The user id, or empty if the token is missing, forged or expired. */
    public Optional<UUID> verify(String token) {
        try {
            String sub = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
            return Optional.of(UUID.fromString(sub));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Reads "Authorization: Bearer <token>". */
    public Optional<UUID> fromHeader(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return verify(authorization.substring(7));
    }
}
