package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AnonymousCsrfRateLimitFilterTests {
    @Test void rejectionDoesNotCreateSessionAndIgnoresForwardedAddress() throws Exception {
        AuthenticationAuditService audit = mock(AuthenticationAuditService.class);
        var filter = new AnonymousCsrfRateLimitFilter(Clock.systemUTC(), audit, 1, Duration.ofMinutes(1), 10);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest first = request();
        filter.doFilter(first, new MockHttpServletResponse(), chain);
        MockHttpServletRequest second = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(second, response, chain);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(second.getSession(false)).isNull();
        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(audit).record("CSRF_ISSUANCE", "RATE_LIMITED", null);
    }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/auth/csrf");
        request.setRemoteAddr("203.0.113.4"); request.addHeader("X-Forwarded-For", "198.51.100.9"); return request;
    }
}
