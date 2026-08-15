package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class IdentityServicesIT {

    private static final char[] ADMIN_PASSWORD = "admin bootstrap passphrase".toCharArray();
    private static final char[] MEMBER_PASSWORD = "member account passphrase".toCharArray();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.10");

    @Autowired
    private AdministratorBootstrapService bootstrapService;

    @Autowired
    private InvitationCreationService invitationCreationService;

    @Autowired
    private InvitationAcceptanceService invitationAcceptanceService;

    @Autowired
    private CredentialVerificationService credentialVerificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void clearIdentityData() {
        jdbcTemplate.update("DELETE FROM job_search_assistant.household_invitation");
        jdbcTemplate.update("DELETE FROM job_search_assistant.user_account");
    }

    @AfterEach
    void clearAfterTest() {
        clearIdentityData();
    }

    @Test
    void bootstrapIsDisabledByDefaultAndNoAccountIsSeeded() {
        assertThat(accountCount()).isZero();
        assertThat(applicationContext.getBeansOfType(AdministratorBootstrapRunner.class)).isEmpty();
        assertThatThrownBy(() -> bootstrapService.bootstrap(
                        false, "admin", "Household Admin", ADMIN_PASSWORD))
                .isInstanceOf(BootstrapRejectedException.class)
                .hasMessage("Administrator bootstrap is disabled");
        assertThat(accountCount()).isZero();
    }

    @Test
    void firstBootstrapCreatesExactlyOneActiveAdministratorAndSecondFails() {
        AccountId accountId = bootstrapAdmin();

        assertThat(jdbcTemplate.queryForMap("""
                        SELECT id, role, status FROM job_search_assistant.user_account
                        WHERE id = ?
                        """, accountId.value()))
                .containsEntry("role", "ADMIN")
                .containsEntry("status", "ACTIVE");
        assertThatThrownBy(() -> bootstrapService.bootstrap(
                        true, "other.admin", "Other Admin", ADMIN_PASSWORD))
                .isInstanceOf(BootstrapRejectedException.class)
                .hasMessage("Administrator bootstrap is no longer available");
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void concurrentBootstrapAllowsExactlyOneAdministrator() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> attempts = List.of(
                    bootstrapAttempt("admin.one", ready, start),
                    bootstrapAttempt("admin.two", ready, start));
            var futures = attempts.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();

            assertThat(futures).extracting(future -> future.get()).containsExactlyInAnyOrder(true, false);
        }
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void onlyActiveAdministratorCanCreateMemberInvitationAndPlaintextIsNotStored() {
        AccountId adminId = bootstrapAdmin();
        String token;
        try (IssuedInvitation issued = invitationCreationService.createMemberInvitation(adminId)) {
            assertThat(issued.toString()).isEqualTo("IssuedInvitation[redacted]");
            token = issued.revealToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThatThrownBy(issued::revealToken).isInstanceOf(IllegalStateException.class);
        }

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM job_search_assistant.household_invitation", String.class);
        assertThat(storedHash).startsWith("sha256:").hasSize(71).doesNotContain(token);

        AuthenticatedIdentity member = acceptNewMember(createInvitation(adminId), "member.one");
        assertThatThrownBy(() -> invitationCreationService.createMemberInvitation(member.accountId()))
                .isInstanceOf(InvitationRejectedException.class);
        jdbcTemplate.update(
                "UPDATE job_search_assistant.user_account SET status = 'DISABLED' WHERE id = ?", adminId.value());
        assertThatThrownBy(() -> invitationCreationService.createMemberInvitation(adminId))
                .isInstanceOf(InvitationRejectedException.class);
        assertThatThrownBy(() -> invitationCreationService.createMemberInvitation(AccountId.generate()))
                .isInstanceOf(InvitationRejectedException.class);
    }

    @Test
    void validInvitationCreatesActiveMemberAndConsumesInvitation() {
        AccountId adminId = bootstrapAdmin();
        String token = createInvitation(adminId);

        AuthenticatedIdentity identity = acceptNewMember(token, "new.member");

        assertThat(identity.role()).isEqualTo(AccountRole.MEMBER);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM job_search_assistant.user_account WHERE id = ?",
                String.class,
                identity.accountId().value())).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT status, consumed_at FROM job_search_assistant.household_invitation"))
                .containsEntry("status", "CONSUMED");
    }

    @Test
    void malformedExpiredRevokedAndConsumedInvitationsFailGenerically() {
        AccountId adminId = bootstrapAdmin();
        assertGenericInvitationFailure("malformed");

        String expired = createInvitation(adminId);
        String expiredHash = new TokenDigester().digest(expired);
        jdbcTemplate.update("""
                UPDATE job_search_assistant.household_invitation
                SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                WHERE token_hash = ?
                """, expiredHash);
        assertGenericInvitationFailure(expired);

        String revoked = createInvitation(adminId);
        jdbcTemplate.update(
                "UPDATE job_search_assistant.household_invitation SET status = 'REVOKED' WHERE token_hash = ?",
                new TokenDigester().digest(revoked));
        assertGenericInvitationFailure(revoked);

        String consumed = createInvitation(adminId);
        acceptNewMember(consumed, "consumed.member");
        assertGenericInvitationFailure(consumed);
    }

    @Test
    void duplicateLoginRollsBackSoInvitationRemainsUsable() {
        AccountId adminId = bootstrapAdmin();
        acceptNewMember(createInvitation(adminId), "existing.member");
        String retryableToken = createInvitation(adminId);

        assertThatThrownBy(() -> acceptNewMember(retryableToken, "existing.member"))
                .isInstanceOf(LoginUnavailableException.class);
        assertThat(invitationStatus(retryableToken)).isEqualTo("PENDING");

        assertThat(acceptNewMember(retryableToken, "different.member").role()).isEqualTo(AccountRole.MEMBER);
    }

    @Test
    void concurrentInvitationAcceptanceAllowsExactlyOneAccount() throws Exception {
        String token = createInvitation(bootstrapAdmin());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(acceptanceAttempt(token, "member.alpha", ready, start));
            var second = executor.submit(acceptanceAttempt(token, "member.beta", ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(accountCount()).isEqualTo(2);
        assertThat(invitationStatus(token)).isEqualTo("CONSUMED");
    }

    @Test
    void credentialVerificationIsGenericAndReturnsOnlyMinimalIdentity() {
        AccountId adminId = bootstrapAdmin();
        AuthenticatedIdentity authenticated = credentialVerificationService.verify("  ADMIN  ", ADMIN_PASSWORD);

        assertThat(authenticated).isEqualTo(new AuthenticatedIdentity(adminId, AccountRole.ADMIN));
        assertThat(AuthenticatedIdentity.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("accountId", "role");

        assertGenericAuthenticationFailure("unknown", ADMIN_PASSWORD);
        assertGenericAuthenticationFailure("admin", "wrong password phrase".toCharArray());
        jdbcTemplate.update(
                "UPDATE job_search_assistant.user_account SET status = 'DISABLED' WHERE id = ?", adminId.value());
        assertGenericAuthenticationFailure("admin", ADMIN_PASSWORD);
        jdbcTemplate.update(
                "UPDATE job_search_assistant.user_account SET status = 'PENDING_ACTIVATION' WHERE id = ?",
                adminId.value());
        assertGenericAuthenticationFailure("admin", ADMIN_PASSWORD);
    }

    private AccountId bootstrapAdmin() {
        return bootstrapService.bootstrap(true, "admin", "Household Admin", ADMIN_PASSWORD);
    }

    private String createInvitation(AccountId adminId) {
        return invitationCreationService.createMemberInvitation(adminId).revealToken();
    }

    private AuthenticatedIdentity acceptNewMember(String token, String loginName) {
        return invitationAcceptanceService.accept(token, loginName, "Household Member", MEMBER_PASSWORD);
    }

    private void assertGenericInvitationFailure(String token) {
        assertThatThrownBy(() -> acceptNewMember(token, "rejected." + UUID.randomUUID().toString().substring(0, 8)))
                .isInstanceOf(InvitationRejectedException.class)
                .hasMessage("Invitation request rejected");
    }

    private void assertGenericAuthenticationFailure(String loginName, char[] password) {
        assertThatThrownBy(() -> credentialVerificationService.verify(loginName, password))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Authentication failed");
    }

    private Callable<Boolean> bootstrapAttempt(String loginName, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                bootstrapService.bootstrap(true, loginName, "Concurrent Admin", ADMIN_PASSWORD);
                return true;
            } catch (BootstrapRejectedException rejected) {
                return false;
            }
        };
    }

    private Callable<Boolean> acceptanceAttempt(
            String token, String loginName, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                acceptNewMember(token, loginName);
                return true;
            } catch (InvitationRejectedException rejected) {
                return false;
            }
        };
    }

    private int accountCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM job_search_assistant.user_account", Integer.class);
    }

    private String invitationStatus(String token) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM job_search_assistant.household_invitation WHERE token_hash = ?",
                String.class,
                new TokenDigester().digest(token));
    }
}
