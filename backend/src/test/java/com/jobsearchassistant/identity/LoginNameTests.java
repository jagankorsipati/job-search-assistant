package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class LoginNameTests {

    @Test
    void normalizesCaseAndSurroundingWhitespace() {
        assertThat(LoginName.of("  Household.Member-1  ").value()).isEqualTo("household.member-1");
    }

    @Test
    void rejectsBlankOrInvalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> LoginName.of("   "));
        assertThatIllegalArgumentException().isThrownBy(() -> LoginName.of("ab"));
        assertThatIllegalArgumentException().isThrownBy(() -> LoginName.of("_member"));
        assertThatIllegalArgumentException().isThrownBy(() -> LoginName.of("member@example.com"));
        assertThatNullPointerException().isThrownBy(() -> LoginName.of(null));
    }
}
