package com.jobsearchassistant.identity;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class SessionValidationFilter extends OncePerRequestFilter {
    private final SessionAccountValidator validator;
    private final AuthenticationAuditService audit;
    private final Clock clock;
    private final Duration absoluteTimeout;

    SessionValidationFilter(SessionAccountValidator validator, AuthenticationAuditService audit, Clock clock,
            SessionSecuritySettings settings) {
        this.validator = validator;
        this.audit = audit;
        this.clock = clock;
        this.absoluteTimeout = settings.absoluteTimeout();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionPrincipal principal) {
            HttpSession session = request.getSession(false);
            boolean absoluteExpired = session == null
                    || clock.millis() - session.getCreationTime() >= absoluteTimeout.toMillis();
            if (absoluteExpired || !validator.isCurrent(principal)) {
                if (session != null) {
                    session.invalidate();
                }
                SecurityContextHolder.clearContext();
                audit.record("SESSION_INVALIDATED", "SUCCEEDED", principal.accountId());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/problem+json");
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"title\":\"Authentication required\",\"status\":401}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
