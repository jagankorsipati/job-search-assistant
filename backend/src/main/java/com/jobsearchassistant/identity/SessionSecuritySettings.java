package com.jobsearchassistant.identity;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
record SessionSecuritySettings(Duration idleTimeout, Duration absoluteTimeout) {
    SessionSecuritySettings(
            @Value("${spring.session.timeout:30m}") Duration idleTimeout,
            @Value("${identity.session.absolute-timeout:12h}") Duration absoluteTimeout) {
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idle session timeout must be positive");
        }
        if (absoluteTimeout.isZero() || absoluteTimeout.isNegative()
                || absoluteTimeout.compareTo(idleTimeout) < 0) {
            throw new IllegalArgumentException("absolute session timeout must be positive and at least the idle timeout");
        }
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
    }
}
