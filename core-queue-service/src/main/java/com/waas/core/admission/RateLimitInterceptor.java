package com.waas.core.admission;

import com.waas.core.auth.JwtService;
import com.waas.core.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Fixed-window rate limit on write requests: N per user (or per IP if not logged in) per window.
 * Same Redis pattern as the referral velocity check: SET NX EX first so the counter always
 * expires, then INCR.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;
    private final JwtService jwt;
    private final int limit;
    private final Duration window;

    public RateLimitInterceptor(StringRedisTemplate redis, JwtService jwt,
                                @Value("${waas.admission.rate-limit:20}") int limit,
                                @Value("${waas.admission.rate-window:10s}") Duration window) {
        this.redis = redis;
        this.jwt = jwt;
        this.limit = limit;
        this.window = window;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("GET".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String who = jwt.fromHeader(request.getHeader(HttpHeaders.AUTHORIZATION))
                .map(UUID::toString)
                .orElse("ip:" + request.getRemoteAddr());
        String key = "ratelimit:" + who;

        redis.opsForValue().setIfAbsent(key, "0", window);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count > limit) {
            Long ttl = redis.getExpire(key);
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ttl == null || ttl < 1 ? 1 : ttl));
            throw ApiException.tooManyRequests("Slow down: max " + limit + " writes per " + window.toSeconds() + "s");
        }
        return true;
    }
}
