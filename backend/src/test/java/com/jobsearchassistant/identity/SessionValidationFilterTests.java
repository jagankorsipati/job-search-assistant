package com.jobsearchassistant.identity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionValidationFilterTests {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void absoluteLifetimeInvalidatesSession() throws Exception {
        Instant now = Instant.parse("2026-08-15T12:00:00Z");
        SessionPrincipal principal = new SessionPrincipal(UUID.randomUUID(), AccountRole.MEMBER, 0);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SessionAccountValidator validator = mock(SessionAccountValidator.class);
        AuthenticationAuditService audit = mock(AuthenticationAuditService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getCreationTime()).thenReturn(now.minus(Duration.ofHours(13)).toEpochMilli());

        var filter = new SessionValidationFilter(validator, audit, Clock.fixed(now, ZoneOffset.UTC),
                new SessionSecuritySettings(Duration.ofMinutes(30), Duration.ofHours(12)));
        filter.doFilter(request, response, mock(FilterChain.class));

        verify(session).invalidate();
        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(401);
        verify(audit).record("SESSION_INVALIDATED", "SUCCEEDED", principal.accountId());
    }
}
