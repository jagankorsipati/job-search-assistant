package com.jobsearchassistant.identity;

import java.util.UUID;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class AccountSessionRevoker {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    AccountSessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    int revoke(UUID accountId) {
        var indexed = sessions.findByPrincipalName(accountId.toString());
        indexed.keySet().forEach(sessions::deleteById);
        return indexed.size();
    }
}
