package com.waas.core.admission;

import com.waas.core.auth.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * POST + "Idempotency-Key" header: the first response is stored in Redis for 24h and replayed
 * for any retry with the same key (per user). A retry while the first is still running gets 409.
 * 5xx responses aren't stored, so the client can retry those for real.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final String PENDING = "PENDING";
    private static final Duration PENDING_TTL = Duration.ofSeconds(30);
    private static final Duration KEEP = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final JwtService jwt;

    public IdempotencyFilter(StringRedisTemplate redis, JwtService jwt) {
        this.redis = redis;
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = jwt.fromHeader(request.getHeader(HttpHeaders.AUTHORIZATION)).map(UUID::toString).orElse("anon");
        String key = "idem:" + user + ":" + request.getHeader(HEADER);

        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, PENDING, PENDING_TTL))) {
            String stored = redis.opsForValue().get(key);
            if (stored == null || PENDING.equals(stored)) {
                writeJson(response, 409, "{\"code\":\"IDEMPOTENCY_IN_PROGRESS\",\"detail\":\"Same request is still running\"}");
            } else {
                int newline = stored.indexOf('\n');   // stored as "<status>\n<body>"
                response.setHeader("Idempotent-Replay", "true");
                writeJson(response, Integer.parseInt(stored.substring(0, newline)), stored.substring(newline + 1));
            }
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } finally {
            int status = wrapper.getStatus();
            if (status >= 500) {
                redis.delete(key);
            } else {
                String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                redis.opsForValue().set(key, status + "\n" + body, KEEP);
            }
            wrapper.copyBodyToResponse();
        }
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
