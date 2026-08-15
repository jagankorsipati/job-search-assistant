package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class AccountLifecycleTests {

    @Test
    void accountIdentifiersRequireAndRetainAUuid() {
        AccountId accountId = AccountId.generate();

        assertThat(accountId.value()).isNotNull();
        assertThatNullPointerException().isThrownBy(() -> new AccountId(null));
    }

    @Test
    void onlyActiveAccountsMayAuthenticate() {
        assertThat(AccountStatus.ACTIVE.canAuthenticate()).isTrue();
        assertThat(AccountStatus.PENDING_ACTIVATION.canAuthenticate()).isFalse();
        assertThat(AccountStatus.DISABLED.canAuthenticate()).isFalse();
    }

    @Test
    void lifecycleAllowsActivationDisablementAndExplicitReactivationOnly() {
        assertThat(AccountStatus.PENDING_ACTIVATION.canTransitionTo(AccountStatus.ACTIVE)).isTrue();
        assertThat(AccountStatus.PENDING_ACTIVATION.canTransitionTo(AccountStatus.DISABLED)).isTrue();
        assertThat(AccountStatus.ACTIVE.canTransitionTo(AccountStatus.DISABLED)).isTrue();
        assertThat(AccountStatus.DISABLED.canTransitionTo(AccountStatus.ACTIVE)).isTrue();
        assertThat(AccountStatus.ACTIVE.canTransitionTo(AccountStatus.PENDING_ACTIVATION)).isFalse();
        assertThat(AccountStatus.ACTIVE.canTransitionTo(AccountStatus.ACTIVE)).isFalse();
        assertThat(AccountStatus.ACTIVE.canTransitionTo(null)).isFalse();
    }

    @Test
    void rolesAreLimitedToAdministratorAndMember() {
        assertThat(AccountRole.values()).containsExactly(AccountRole.ADMIN, AccountRole.MEMBER);
    }
}
