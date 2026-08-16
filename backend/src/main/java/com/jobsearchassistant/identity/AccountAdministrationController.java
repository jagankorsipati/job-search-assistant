package com.jobsearchassistant.identity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@RequestMapping("/api/admin/accounts")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class AccountAdministrationController {
    record AccountResponse(UUID accountId, String loginName, String displayName, AccountRole role,
            AccountStatus status, Instant createdAt) {
        static AccountResponse from(ManagedAccount account) {
            return new AccountResponse(account.accountId(), account.loginName(), account.displayName(), account.role(),
                    account.status(), account.createdAt());
        }
    }

    private final AccountAdministrationService service;

    AccountAdministrationController(AccountAdministrationService service) { this.service = service; }

    @GetMapping
    ResponseEntity<List<AccountResponse>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list().stream().map(AccountResponse::from).toList());
    }

    @PostMapping("/{accountId}/disable")
    ResponseEntity<?> disable(@PathVariable UUID accountId) {
        return transition(() -> service.disable(accountId));
    }

    @PostMapping("/{accountId}/reactivate")
    ResponseEntity<?> reactivate(@PathVariable UUID accountId) {
        return transition(() -> service.reactivate(accountId));
    }

    private ResponseEntity<?> transition(Runnable operation) {
        try {
            operation.run();
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (AccountTransitionRejectedException rejected) {
            return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                    .body(Map.of("title", "Account transition is not available", "status", 409,
                            "code", "invalid_transition"));
        }
    }
}
