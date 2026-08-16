package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.AuthenticatedActor;
import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class AccountAdministrationServiceTests {
    @Test
    void committedDisableRemainsEffectiveWhenSessionDeletionFails() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AccountAdministrationRepository repository = mock(AccountAdministrationRepository.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        AccountSessionRevoker sessions = mock(AccountSessionRevoker.class);
        AuthenticationAuditService audit = mock(AuthenticationAuditService.class);
        TransactionTemplate transactions = immediateTransactions();
        when(actors.currentActor()).thenReturn(new AuthenticatedActor(actorId, ActorRole.ADMIN));
        when(repository.disableActiveMember(any(), any())).thenReturn(true);
        when(sessions.revoke(targetId)).thenThrow(new IllegalStateException("database unavailable"));

        new AccountAdministrationService(repository, actors, sessions, audit, transactions,
                Clock.fixed(Instant.parse("2026-08-15T12:00:00Z"), ZoneOffset.UTC)).disable(targetId);

        verify(repository).disableActiveMember(targetId, Instant.parse("2026-08-15T12:00:00Z"));
        verify(audit).record("ACCOUNT_DISABLED", "SUCCEEDED", actorId, targetId);
        verify(audit).record("ACCOUNT_SESSIONS_REVOKED", "FAILED", actorId, targetId);
    }

    @Test
    void rejectedTransitionDoesNotAttemptSessionRevocation() {
        var repository = mock(AccountAdministrationRepository.class);
        var actors = mock(CurrentActorProvider.class);
        var sessions = mock(AccountSessionRevoker.class);
        var audit = mock(AuthenticationAuditService.class);
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(actors.currentActor()).thenReturn(new AuthenticatedActor(actorId, ActorRole.ADMIN));
        when(repository.disableActiveMember(any(), any())).thenReturn(false);
        var service = new AccountAdministrationService(repository, actors, sessions, audit, immediateTransactions(),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.disable(targetId)).isInstanceOf(AccountTransitionRejectedException.class);
        verify(audit).record("ACCOUNT_ADMINISTRATION_REJECTED", "FAILED", actorId, targetId);
        org.mockito.Mockito.verifyNoInteractions(sessions);
    }

    @Test
    void retentionSettingsEnforceSafeBounds() {
        assertThat(new AuditRetentionSettings(Duration.ofDays(90), 500).retention()).isEqualTo(Duration.ofDays(90));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditRetentionSettings(Duration.ofDays(29), 500));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditRetentionSettings(Duration.ofDays(366), 500));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditRetentionSettings(Duration.ofDays(90), 49));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditRetentionSettings(Duration.ofDays(90), 5001));
    }

    @Test
    void scheduledRetentionFailureIsContained() {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(any(String.class))).thenThrow(new IllegalStateException("database unavailable"));
        var retention = new AuthenticationAuditRetentionService(jdbc,
                new AuditRetentionSettings(Duration.ofDays(90), 500), Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatCode(retention::scheduledCleanup).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private TransactionTemplate immediateTransactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<Boolean>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));
        return template;
    }
}
