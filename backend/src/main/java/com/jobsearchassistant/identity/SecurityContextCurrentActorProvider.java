package com.jobsearchassistant.identity;

import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.AuthenticatedActor;
import com.jobsearchassistant.identity.api.CurrentActorProvider;
import com.jobsearchassistant.identity.api.UnauthenticatedActorException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class SecurityContextCurrentActorProvider implements CurrentActorProvider {
    private final SessionAccountValidator validator;

    SecurityContextCurrentActorProvider(SessionAccountValidator validator) { this.validator = validator; }

    @Override
    public AuthenticatedActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)
                || !validator.isCurrent(principal)) {
            throw new UnauthenticatedActorException();
        }
        return new AuthenticatedActor(principal.accountId(), ActorRole.valueOf(principal.role().name()));
    }
}
