package com.jobsearchassistant.identity;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class AnonymousCsrfRateLimitFilter extends OncePerRequestFilter {
    private record Bucket(Instant started, int attempts) { }
    private final Clock clock; private final AuthenticationAuditService audit;
    private final int limit; private final int maxKeys; private final Duration window;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, .75f, true);

    AnonymousCsrfRateLimitFilter(Clock clock, AuthenticationAuditService audit,
            @Value("${identity.csrf-rate-limit.source-attempts:30}") int limit,
            @Value("${identity.csrf-rate-limit.window:15m}") Duration window,
            @Value("${identity.csrf-rate-limit.max-keys:500}") int maxKeys) {
        if (limit < 1 || maxKeys < 1 || window.isZero() || window.isNegative())
            throw new IllegalArgumentException("CSRF rate-limit configuration is unsafe");
        this.clock = clock; this.audit = audit; this.limit = limit; this.window = window; this.maxKeys = maxKeys;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"GET".equals(request.getMethod()) || !"/api/auth/csrf".equals(request.getRequestURI())
                || request.getSession(false) != null) { chain.doFilter(request, response); return; }
        long retry = acquire(request.getRemoteAddr());
        if (retry == 0) { chain.doFilter(request, response); return; }
        audit.record("CSRF_ISSUANCE", "RATE_LIMITED", null);
        response.setStatus(429); response.setContentType("application/problem+json");
        response.setHeader("Retry-After", Long.toString(retry)); response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"title\":\"Request temporarily unavailable\",\"status\":429}");
    }

    private synchronized long acquire(String source) {
        Instant now = clock.instant();
        buckets.entrySet().removeIf(e -> !now.isBefore(e.getValue().started().plus(window)));
        Bucket bucket = buckets.get(source);
        if (bucket == null) buckets.put(source, new Bucket(now, 1));
        else if (bucket.attempts() >= limit)
            return Math.max(1, Duration.between(now, bucket.started().plus(window)).toSeconds());
        else buckets.put(source, new Bucket(bucket.started(), bucket.attempts() + 1));
        while (buckets.size() > maxKeys) buckets.remove(buckets.keySet().iterator().next());
        return 0;
    }
}
