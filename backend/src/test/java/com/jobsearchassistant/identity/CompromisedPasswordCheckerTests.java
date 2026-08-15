package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class CompromisedPasswordCheckerTests {
    @Test
    void rejectsCommonAndContextualPasswords() {
        var checker = new OfflineCompromisedPasswordChecker(
                new ClassPathResource("security/compromised-passwords-sha256.txt"));
        assertThatThrownBy(() -> checker.validate("password".toCharArray(), "member", "Household Member"))
                .isInstanceOf(PasswordRejectedException.class);
        assertThatThrownBy(() -> checker.validate("member-has-a-long-passphrase".toCharArray(), "member", "Person"))
                .isInstanceOf(PasswordRejectedException.class);
    }

    @Test
    void missingOrCorruptBlocklistFailsClosed() {
        assertThatThrownBy(() -> new OfflineCompromisedPasswordChecker(new ClassPathResource("missing.txt")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OfflineCompromisedPasswordChecker(
                new ByteArrayResource("# wrong\nnot-a-digest".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalStateException.class);
    }
}
