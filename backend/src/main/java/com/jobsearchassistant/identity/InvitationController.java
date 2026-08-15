package com.jobsearchassistant.identity;

import java.util.Arrays;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class InvitationController {
    record InvitationResponse(String token, java.time.Instant expiresAt) {
        @Override public String toString() { return "InvitationResponse[token=redacted, expiresAt=" + expiresAt + "]"; }
    }
    record AcceptanceRequest(String token, String loginName, String displayName, char[] password) {
        @Override public String toString() { return "AcceptanceRequest[redacted]"; }
    }
    private final InvitationCreationService creation;
    private final InvitationAcceptanceService acceptance;
    private final CompromisedPasswordChecker compromisedPasswords;
    private final InvitationRequestRateLimiter limiter;
    private final TokenDigester tokenDigester;
    private final AuthenticationAuditService audit;

    InvitationController(InvitationCreationService creation, InvitationAcceptanceService acceptance,
            CompromisedPasswordChecker compromisedPasswords, InvitationRequestRateLimiter limiter,
            TokenDigester tokenDigester, AuthenticationAuditService audit) {
        this.creation = creation; this.acceptance = acceptance; this.compromisedPasswords = compromisedPasswords;
        this.limiter = limiter; this.tokenDigester = tokenDigester; this.audit = audit;
    }

    @PostMapping("/api/admin/invitations")
    ResponseEntity<InvitationResponse> create(Authentication authentication) {
        SessionPrincipal principal = (SessionPrincipal) authentication.getPrincipal();
        try (IssuedInvitation invitation = creation.createMemberInvitation(new AccountId(principal.accountId()))) {
            String token = invitation.revealToken();
            audit.record("INVITATION_ISSUED", "SUCCEEDED", principal.accountId());
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(new InvitationResponse(token, invitation.expiresAt()));
        }
    }

    @PostMapping("/api/invitations/accept")
    ResponseEntity<?> accept(@RequestBody AcceptanceRequest body, HttpServletRequest request) {
        char[] password = body == null ? null : body.password();
        String token = body == null ? null : body.token();
        String loginName = body == null ? null : body.loginName();
        String displayName = body == null ? null : body.displayName();
        String digest = tokenDigester.digest(token == null ? "" : token);
        var decision = limiter.acquire(request.getRemoteAddr(), digest);
        if (!decision.allowed()) {
            if (password != null) Arrays.fill(password, '\0');
            audit.record("INVITATION_ACCEPTANCE", "RATE_LIMITED", null);
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()))
                    .cacheControl(CacheControl.noStore()).body(generic("Request temporarily unavailable", 429));
        }
        try {
            compromisedPasswords.validate(password, loginName, displayName);
            AuthenticatedIdentity identity = acceptance.accept(token, loginName, displayName, password);
            audit.record("INVITATION_ACCEPTED", "SUCCEEDED", identity.accountId().value());
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(Map.of("accountId", identity.accountId().value(), "role", identity.role()));
        } catch (PasswordRejectedException rejected) {
            return ResponseEntity.unprocessableEntity().cacheControl(CacheControl.noStore())
                    .body(Map.of("title", rejected.getMessage(), "status", 422, "code", "password_rejected"));
        } catch (LoginUnavailableException unavailable) {
            audit.record("INVITATION_REJECTED", "FAILED", null);
            return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                    .body(Map.of("title", "Login name is unavailable", "status", 409, "code", "login_unavailable"));
        } catch (InvitationRejectedException | IllegalArgumentException rejected) {
            audit.record("INVITATION_REJECTED", "FAILED", null);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore())
                    .body(generic("Invitation is invalid or no longer available", 400));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private Map<String, Object> generic(String title, int status) { return Map.of("title", title, "status", status); }
}
