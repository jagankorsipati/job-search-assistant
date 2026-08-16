package com.jobsearchassistant.identity;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.jobsearchassistant.identity.api.AuthenticatedActor;
import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class AccountAdministrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountAdministrationService.class);
    private final AccountAdministrationRepository repository;
    private final CurrentActorProvider actors;
    private final AccountSessionRevoker sessions;
    private final AuthenticationAuditService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    AccountAdministrationService(AccountAdministrationRepository repository, CurrentActorProvider actors,
            AccountSessionRevoker sessions, AuthenticationAuditService audit, TransactionTemplate transactions,
            Clock clock) {
        this.repository = repository;
        this.actors = actors;
        this.sessions = sessions;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    List<ManagedAccount> list() {
        administrator();
        return repository.findAll();
    }

    void disable(UUID targetAccountId) {
        AuthenticatedActor actor = administrator();
        Boolean changed = transactions.execute(status -> repository.disableActiveMember(targetAccountId, clock.instant()));
        if (!Boolean.TRUE.equals(changed)) {
            audit.record("ACCOUNT_ADMINISTRATION_REJECTED", "FAILED", actor.accountId(), targetAccountId);
            throw new AccountTransitionRejectedException();
        }
        audit.record("ACCOUNT_DISABLED", "SUCCEEDED", actor.accountId(), targetAccountId);
        try {
            sessions.revoke(targetAccountId);
            audit.record("ACCOUNT_SESSIONS_REVOKED", "SUCCEEDED", actor.accountId(), targetAccountId);
        } catch (RuntimeException failure) {
            LOGGER.warn("Account sessions could not be revoked after account disablement");
            audit.record("ACCOUNT_SESSIONS_REVOKED", "FAILED", actor.accountId(), targetAccountId);
        }
    }

    void reactivate(UUID targetAccountId) {
        AuthenticatedActor actor = administrator();
        Boolean changed = transactions.execute(
                status -> repository.reactivateDisabledMember(targetAccountId, clock.instant()));
        if (!Boolean.TRUE.equals(changed)) {
            audit.record("ACCOUNT_ADMINISTRATION_REJECTED", "FAILED", actor.accountId(), targetAccountId);
            throw new AccountTransitionRejectedException();
        }
        audit.record("ACCOUNT_REACTIVATED", "SUCCEEDED", actor.accountId(), targetAccountId);
    }

    private AuthenticatedActor administrator() {
        AuthenticatedActor actor = actors.currentActor();
        if (actor.role() != ActorRole.ADMIN) throw new AccountTransitionRejectedException();
        return actor;
    }
}
