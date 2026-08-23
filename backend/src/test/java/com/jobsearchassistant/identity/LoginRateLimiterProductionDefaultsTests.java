package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The disposable browser-E2E backend (scripts/run-browser-e2e.ps1) raises the
 * login-rate-limit ceiling with process-scoped environment variables so that
 * the Playwright suite's repeated administrator logins across specs and
 * verification-matrix re-runs don't collide with production limits. That
 * override must never leak into the shipped configuration. This test reads
 * the packaged application.properties directly (not an overridable Spring
 * context) and pins the exact production defaults, then proves those exact
 * defaults are what LoginRateLimiter actually enforces.
 */
class LoginRateLimiterProductionDefaultsTests {
    private static final Pattern LOGIN_ATTEMPTS = defaultPattern("identity.login-rate-limit.login-attempts",
            "LOGIN_RATE_LIMIT_LOGIN_ATTEMPTS");
    private static final Pattern SOURCE_ATTEMPTS = defaultPattern("identity.login-rate-limit.source-attempts",
            "LOGIN_RATE_LIMIT_SOURCE_ATTEMPTS");
    private static final Pattern WINDOW = defaultPattern("identity.login-rate-limit.window",
            "LOGIN_RATE_LIMIT_WINDOW");
    private static final Pattern MAX_KEYS = defaultPattern("identity.login-rate-limit.max-keys",
            "LOGIN_RATE_LIMIT_MAX_KEYS");

    private static Pattern defaultPattern(String property, String envVar) {
        return Pattern.compile(Pattern.quote(property) + "=\\$\\{" + Pattern.quote(envVar) + ":([^}]+)\\}");
    }

    @Test void shippedDefaultsAreUnchanged() throws IOException {
        String properties = readApplicationProperties();
        assertThat(defaultValue(properties, LOGIN_ATTEMPTS)).isEqualTo("5");
        assertThat(defaultValue(properties, SOURCE_ATTEMPTS)).isEqualTo("20");
        assertThat(defaultValue(properties, WINDOW)).isEqualTo("15m");
        assertThat(defaultValue(properties, MAX_KEYS)).isEqualTo("1000");
    }

    @Test void shippedDefaultsRejectTheSixthLoginAttemptWithinTheWindow() {
        // The exact production defaults asserted above, applied directly to the
        // component under test rather than read back through the string.
        LoginRateLimiter limiter = new LoginRateLimiter(Clock.systemUTC(), 20, 5, Duration.ofMinutes(15), 1000);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.acquire("198.51.100.10", "same.login").allowed())
                    .as("attempt %d of 5 for one login name", attempt).isTrue();
        }
        assertThat(limiter.acquire("198.51.100.10", "same.login").allowed())
                .as("a 6th attempt within the window must be rejected by the production default")
                .isFalse();
    }

    private static String readApplicationProperties() throws IOException {
        try (InputStream stream = new ClassPathResource("application.properties").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String defaultValue(String properties, Pattern pattern) {
        Matcher matcher = pattern.matcher(properties);
        assertThat(matcher.find())
                .as("property matching %s must still be present in application.properties", pattern)
                .isTrue();
        return matcher.group(1);
    }
}
