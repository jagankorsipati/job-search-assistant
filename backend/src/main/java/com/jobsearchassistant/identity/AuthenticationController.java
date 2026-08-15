package com.jobsearchassistant.identity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class AuthenticationController {
    record LoginRequest(String loginName, char[] password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    record IdentityResponse(java.util.UUID accountId, AccountRole role) { }
    record CsrfResponse(String token, String headerName, String parameterName) {
        @Override public String toString() { return "CsrfResponse[token=redacted]"; }
    }

    private static final Map<String, Object> AUTHENTICATION_FAILED =
            Map.of("title", "Authentication failed", "status", 401);
    private final CredentialVerificationService credentials;
    private final LoginRateLimiter limiter;
    private final AuthenticationAuditService audit;
    private final boolean secureCookie;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    AuthenticationController(CredentialVerificationService credentials, LoginRateLimiter limiter,
            AuthenticationAuditService audit, @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookie) {
        this.credentials = credentials;
        this.limiter = limiter;
        this.audit = audit;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/csrf")
    ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getToken(), token.getHeaderName(), token.getParameterName()));
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        char[] password = body == null ? null : body.password();
        String loginName = body == null ? null : body.loginName();
        String limiterLogin = loginName == null ? "" : loginName.strip().toLowerCase(Locale.ROOT);
        LoginRateLimiter.Decision decision = limiter.acquire(request.getRemoteAddr(), limiterLogin);
        if (!decision.allowed()) {
            if (password != null) Arrays.fill(password, '\0');
            audit.record("LOGIN", "RATE_LIMITED", null);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("title", "Authentication temporarily unavailable", "status", 429));
        }
        try {
            SessionPrincipal principal = credentials.verifyForSession(loginName, password);
            HttpSession session = request.getSession(true);
            request.changeSessionId();
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            authentication.eraseCredentials();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            audit.record("LOGIN", "SUCCEEDED", principal.accountId());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new IdentityResponse(principal.accountId(), principal.role()));
        } catch (AuthenticationFailedException failure) {
            audit.record("LOGIN", "FAILED", null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                    .body(AUTHENTICATION_FAILED);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    @GetMapping("/me")
    ResponseEntity<IdentityResponse> me(Authentication authentication) {
        SessionPrincipal principal = (SessionPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new IdentityResponse(principal.accountId(), principal.role()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        SessionPrincipal principal = (SessionPrincipal) authentication.getPrincipal();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        audit.record("LOGOUT", "SUCCEEDED", principal.accountId());
        response.addHeader(HttpHeaders.SET_COOKIE, "JSA_SESSION=; Path=/; Max-Age=0; HttpOnly; "
                + (secureCookie ? "Secure; " : "") + "SameSite=Strict");
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
