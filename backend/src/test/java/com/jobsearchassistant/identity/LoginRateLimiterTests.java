package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTests {
    @Test
    void rejectsAtThresholdAndExpiresUsingInjectedClock() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock, 10, 2, Duration.ofMinutes(1), 20);

        assertThat(limiter.acquire("127.0.0.1", "member").allowed()).isTrue();
        assertThat(limiter.acquire("127.0.0.1", "member").allowed()).isTrue();
        LoginRateLimiter.Decision rejected = limiter.acquire("127.0.0.1", "member");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isPositive();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.acquire("127.0.0.1", "member").allowed()).isTrue();
    }

    @Test
    void storageIsBounded() {
        LoginRateLimiter limiter = new LoginRateLimiter(Clock.systemUTC(), 10, 10, Duration.ofHours(1), 6);
        for (int index = 0; index < 20; index++) {
            limiter.acquire("source-" + index, "login-" + index);
        }
        assertThat(limiter.size()).isLessThanOrEqualTo(6);
    }

    @Test
    void concurrentAttemptsCannotExceedThreshold() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(Clock.systemUTC(), 50, 5, Duration.ofMinutes(1), 20);
        AtomicInteger allowed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(10)) {
            for (int index = 0; index < 25; index++) {
                executor.submit(() -> {
                    if (limiter.acquire("127.0.0.1", "member").allowed()) allowed.incrementAndGet();
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(allowed).hasValue(5);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-15T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
    }
}
