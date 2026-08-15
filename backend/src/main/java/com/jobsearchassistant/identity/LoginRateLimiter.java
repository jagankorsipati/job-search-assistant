package com.jobsearchassistant.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class LoginRateLimiter {
    record Decision(boolean allowed, long retryAfterSeconds) { }
    private record Bucket(Instant windowStarted, int attempts) { }

    private final Clock clock;
    private final int sourceLimit;
    private final int loginLimit;
    private final Duration window;
    private final int maxKeys;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, .75f, true);

    LoginRateLimiter(
            Clock clock,
            @Value("${identity.login-rate-limit.source-attempts:20}") int sourceLimit,
            @Value("${identity.login-rate-limit.login-attempts:5}") int loginLimit,
            @Value("${identity.login-rate-limit.window:15m}") Duration window,
            @Value("${identity.login-rate-limit.max-keys:1000}") int maxKeys) {
        if (sourceLimit < 1 || loginLimit < 1 || window.isZero() || window.isNegative() || maxKeys < 2) {
            throw new IllegalArgumentException("login rate-limit configuration is unsafe");
        }
        this.clock = clock;
        this.sourceLimit = sourceLimit;
        this.loginLimit = loginLimit;
        this.window = window;
        this.maxKeys = maxKeys;
    }

    synchronized Decision acquire(String remoteAddress, String normalizedLogin) {
        Instant now = clock.instant();
        expire(now);
        Decision source = acquire("source:" + remoteAddress, sourceLimit, now);
        Decision login = acquire("login:" + digest(normalizedLogin), loginLimit, now);
        trim();
        if (!source.allowed() || !login.allowed()) {
            return new Decision(false, Math.max(source.retryAfterSeconds(), login.retryAfterSeconds()));
        }
        return new Decision(true, 0);
    }

    synchronized int size() {
        expire(clock.instant());
        return buckets.size();
    }

    private Decision acquire(String key, int limit, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null || !now.isBefore(bucket.windowStarted().plus(window))) {
            buckets.put(key, new Bucket(now, 1));
            return new Decision(true, 0);
        }
        if (bucket.attempts() >= limit) {
            long retry = Math.max(1, Duration.between(now, bucket.windowStarted().plus(window)).toSeconds());
            return new Decision(false, retry);
        }
        buckets.put(key, new Bucket(bucket.windowStarted(), bucket.attempts() + 1));
        return new Decision(true, 0);
    }

    private void expire(Instant now) {
        buckets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().windowStarted().plus(window)));
    }

    private void trim() {
        while (buckets.size() > maxKeys) {
            buckets.remove(buckets.keySet().iterator().next());
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
