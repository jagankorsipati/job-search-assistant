package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PasswordSecurityTests {

    private final PasswordPolicy policy = new PasswordPolicy();
    private final PasswordHasher hasher = new Argon2idPasswordHasher();

    @Test
    void policyEnforcesMinimumAndMaximumUnicodeCodePointBoundaries() {
        policy.validate("a".repeat(PasswordPolicy.MINIMUM_CODE_POINTS).toCharArray());
        policy.validate("🙂".repeat(PasswordPolicy.MAXIMUM_CODE_POINTS).toCharArray());

        assertThatIllegalArgumentException().isThrownBy(
                () -> policy.validate("a".repeat(PasswordPolicy.MINIMUM_CODE_POINTS - 1).toCharArray()));
        assertThatIllegalArgumentException().isThrownBy(
                () -> policy.validate("🙂".repeat(PasswordPolicy.MAXIMUM_CODE_POINTS + 1).toCharArray()));
    }

    @Test
    void policyAcceptsUnicodePassphrasesAndDoesNotRequireComposition() {
        policy.validate("correct horse battery staple".toCharArray());
        policy.validate("長い安全な合言葉です長い安全な合言葉".toCharArray());
    }

    @Test
    void argon2idHashesAreSelfDescribingSaltedAndVerifiableWithoutTrimming() {
        char[] password = "  passphrase with spaces  ".toCharArray();
        String firstHash = hasher.hash(password);
        String secondHash = hasher.hash(password);

        assertThat(firstHash).startsWith("$argon2id$").isNotEqualTo(secondHash);
        assertThat(hasher.matches(password, firstHash)).isTrue();
        assertThat(hasher.matches("passphrase with spaces".toCharArray(), firstHash)).isFalse();
    }
}
