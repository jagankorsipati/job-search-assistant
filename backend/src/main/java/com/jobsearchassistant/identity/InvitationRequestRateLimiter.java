package com.jobsearchassistant.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class InvitationRequestRateLimiter {
    record Decision(boolean allowed, long retryAfterSeconds) { }
    private record Bucket(Instant started, int attempts) { }
    private final Clock clock;
    private final Duration window;
    private final int sourceLimit;
    private final int tokenLimit;
    private final int maxKeys;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, .75f, true);

    InvitationRequestRateLimiter(Clock clock,
            @Value("${identity.invitation-accept-rate-limit.source-attempts:20}") int sourceLimit,
            @Value("${identity.invitation-accept-rate-limit.token-attempts:5}") int tokenLimit,
            @Value("${identity.invitation-accept-rate-limit.window:15m}") Duration window,
            @Value("${identity.invitation-accept-rate-limit.max-keys:1000}") int maxKeys) {
        if (sourceLimit < 1 || tokenLimit < 1 || maxKeys < 2 || window.isZero() || window.isNegative())
            throw new IllegalArgumentException("invitation rate-limit configuration is unsafe");
        this.clock = clock; this.sourceLimit = sourceLimit; this.tokenLimit = tokenLimit;
        this.window = window; this.maxKeys = maxKeys;
    }

    synchronized Decision acquire(String source, String tokenDigest) {
        Instant now = clock.instant();
        buckets.entrySet().removeIf(e -> !now.isBefore(e.getValue().started().plus(window)));
        Decision a = take("s:" + source, sourceLimit, now);
        Decision b = take("t:" + tokenDigest, tokenLimit, now);
        while (buckets.size() > maxKeys) buckets.remove(buckets.keySet().iterator().next());
        return a.allowed() && b.allowed() ? new Decision(true, 0)
                : new Decision(false, Math.max(a.retryAfterSeconds(), b.retryAfterSeconds()));
    }

    private Decision take(String key, int limit, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null || !now.isBefore(bucket.started().plus(window))) {
            buckets.put(key, new Bucket(now, 1)); return new Decision(true, 0);
        }
        if (bucket.attempts() >= limit)
            return new Decision(false, Math.max(1, Duration.between(now, bucket.started().plus(window)).toSeconds()));
        buckets.put(key, new Bucket(bucket.started(), bucket.attempts() + 1)); return new Decision(true, 0);
    }
}
