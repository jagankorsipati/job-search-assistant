package com.jobsearchassistant.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CareerFactTests {

    @Test
    void draftFactCanBeConfirmedAndThenUsedForGeneratedContent() {
        CareerFact fact = draftFact("Built a billing reconciliation service.");

        CareerFact confirmed = fact.confirm();

        assertThat(confirmed.status()).isEqualTo(CareerFactStatus.CONFIRMED);
        assertThat(confirmed.eligibleForGeneratedContent()).isTrue();
        assertThat(confirmed.ownerAccountId()).isEqualTo(fact.ownerAccountId());
    }

    @Test
    void confirmedOrImportedTextCannotBeSilentlyStrengthened() {
        CareerFact confirmed = draftFact("Maintained internal deployment scripts.").confirm();

        CareerFact revised = confirmed.reviseContent("Owned internal deployment scripts.");

        assertThat(revised.status()).isEqualTo(CareerFactStatus.DRAFT);
        assertThat(revised.eligibleForGeneratedContent()).isFalse();
    }

    @Test
    void archivedFactsRequireExplicitRestorationBeforeModificationOrConfirmation() {
        CareerFact archived = draftFact("Completed Java certification.").confirm().archive();

        assertThat(archived.eligibleForGeneratedContent()).isFalse();
        assertThatThrownBy(archived::confirm).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> archived.reviseContent("Completed advanced Java certification."))
                .isInstanceOf(IllegalStateException.class);
        assertThat(archived.restoreToDraft().status()).isEqualTo(CareerFactStatus.DRAFT);
    }

    @Test
    void factsRequireBoundedNonblankContent() {
        assertThatThrownBy(() -> draftFact(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("factualContent");
    }

    @Test
    void factsValidateDateRangesAndPresentActivities() {
        assertThatThrownBy(() -> factWithDates(
                        LocalDate.parse("2024-01-01"), LocalDate.parse("2023-12-31"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endedOn");

        assertThatThrownBy(() -> factWithDates(
                        LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ongoing");
    }

    private static CareerFact draftFact(String content) {
        return factWithDates(content, LocalDate.parse("2023-01-01"), null, true);
    }

    private static CareerFact factWithDates(LocalDate startedOn, LocalDate endedOn, boolean ongoing) {
        return factWithDates("Worked on a bounded household-safe fact.", startedOn, endedOn, ongoing);
    }

    private static CareerFact factWithDates(String content, LocalDate startedOn, LocalDate endedOn, boolean ongoing) {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        return new CareerFact(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CareerFactCategory.EMPLOYMENT,
                CareerFactStatus.DRAFT,
                content,
                "Example Co",
                "Software Engineer",
                "Remote",
                startedOn,
                endedOn,
                ongoing,
                now,
                now,
                0);
    }
}
