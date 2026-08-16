package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.UUID;

import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.AuthenticatedActor;
import com.jobsearchassistant.identity.api.CurrentActorProvider;
import com.jobsearchassistant.identity.api.UnauthenticatedActorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentActorProviderTests {
    private final SessionAccountValidator validator = mock(SessionAccountValidator.class);
    private final CurrentActorProvider provider = new SecurityContextCurrentActorProvider(validator);

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void resolvesCurrentMemberAndAdministratorWithoutExposingSessionInternals() {
        UUID memberId = authenticate(AccountRole.MEMBER, true);
        assertThat(provider.currentActor()).isEqualTo(new AuthenticatedActor(memberId, ActorRole.MEMBER));

        UUID adminId = authenticate(AccountRole.ADMIN, true);
        assertThat(provider.currentActor()).isEqualTo(new AuthenticatedActor(adminId, ActorRole.ADMIN));

        assertThat(List.of(AuthenticatedActor.class.getRecordComponents()).stream()
                .map(RecordComponent::getName)).containsExactly("accountId", "role");
        assertThat(CurrentActorProvider.class.getMethods())
                .filteredOn(method -> method.getDeclaringClass() == CurrentActorProvider.class)
                .allSatisfy(method -> {
                    assertThat(method.getParameterCount()).isZero();
                    assertThat(method.getReturnType().getPackageName()).isEqualTo("com.jobsearchassistant.identity.api");
                });
    }

    @Test void rejectsAnonymousUnexpectedClearedAndStaleContextsIdentically() {
        assertUnauthenticated();

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertUnauthenticated();

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("browser-supplied-id", null, List.of()));
        assertUnauthenticated();

        authenticate(AccountRole.MEMBER, false);
        assertUnauthenticated();

        SecurityContextHolder.clearContext();
        assertUnauthenticated();
    }

    private UUID authenticate(AccountRole role, boolean current) {
        SessionPrincipal principal = new SessionPrincipal(UUID.randomUUID(), role, 7);
        when(validator.isCurrent(principal)).thenReturn(current);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
        return principal.accountId();
    }

    private void assertUnauthenticated() {
        assertThatThrownBy(provider::currentActor)
                .isExactlyInstanceOf(UnauthenticatedActorException.class)
                .hasMessage("Authentication required");
    }
}
