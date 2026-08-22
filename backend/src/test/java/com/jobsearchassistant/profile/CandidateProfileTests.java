package com.jobsearchassistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CandidateProfileTests {

    @Test
    void profileRequiresImmutableOwnerAndDisplayName() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");

        CandidateProfile profile = new CandidateProfile(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "  Casey   Candidate  ",
                "Builder of useful systems",
                null,
                "Remote or Seattle",
                "Backend engineer; platform engineer",
                "Authorized to work in the United States",
                "Remote first",
                now,
                now,
                0);

        assertThat(profile.professionalDisplayName()).isEqualTo("Casey Candidate");
    }

    @Test
    void profileRejectsBlankOrOversizedRequiredText() {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");

        assertThatThrownBy(() -> new CandidateProfile(
                        UUID.randomUUID(), UUID.randomUUID(), " ", null, null, null, null, null, null, now, now, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("professionalDisplayName");
    }

    @Test
    void profileRejectsInvalidTimestampsAndVersions() {
        Instant createdAt = Instant.parse("2026-08-22T12:00:00Z");
        Instant updatedAt = Instant.parse("2026-08-22T11:59:00Z");

        assertThatThrownBy(() -> new CandidateProfile(
                        UUID.randomUUID(), UUID.randomUUID(), "Casey", null, null, null, null, null, null,
                        createdAt, updatedAt, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt");

        assertThatThrownBy(() -> new CandidateProfile(
                        UUID.randomUUID(), UUID.randomUUID(), "Casey", null, null, null, null, null, null,
                        createdAt, createdAt, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }
}
