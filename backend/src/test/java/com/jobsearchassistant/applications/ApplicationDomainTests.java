package com.jobsearchassistant.applications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApplicationDomainTests {
    private final Instant now = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void statusTransitionMatrixAllowsOnlyDocumentedTransitions() {
        assertThat(ApplicationStatus.DRAFT.canTransitionTo(ApplicationStatus.READY_TO_APPLY)).isTrue();
        assertThat(ApplicationStatus.READY_TO_APPLY.canTransitionTo(ApplicationStatus.APPLIED)).isTrue();
        assertThat(ApplicationStatus.APPLIED.canTransitionTo(ApplicationStatus.INTERVIEWING)).isTrue();
        assertThat(ApplicationStatus.INTERVIEWING.canTransitionTo(ApplicationStatus.INTERVIEWING)).isTrue();
        assertThat(ApplicationStatus.OFFER.canTransitionTo(ApplicationStatus.ACCEPTED)).isTrue();
        assertThat(ApplicationStatus.ACCEPTED.terminal()).isTrue();
        assertThat(ApplicationStatus.ACCEPTED.canTransitionTo(ApplicationStatus.DRAFT)).isFalse();
        assertThat(ApplicationStatus.DRAFT.canTransitionTo(ApplicationStatus.APPLIED)).isFalse();
    }

    @Test
    void applicationEnforcesAppliedTimestampNextActionAndArchivalRules() {
        JobApplication draft = new JobApplication(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ApplicationStatus.DRAFT, null, NextAction.optional(" Follow up ", LocalDate.parse("2026-08-30")),
                " private ", now, now, now, 0, now);

        assertThat(draft.nextAction().text()).isEqualTo("Follow up");
        assertThat(draft.privateNotes()).isEqualTo("private");
        assertThat(draft.archived()).isTrue();

        assertThatThrownBy(() -> new JobApplication(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ApplicationStatus.APPLIED, null, null, null, now, now, now, 0, null))
                .hasMessageContaining("appliedAt is required");
        assertThatThrownBy(() -> new JobApplication(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ApplicationStatus.READY_TO_APPLY, now, null, null, now, now, now, 0, null))
                .hasMessageContaining("appliedAt must be absent");
        assertThatThrownBy(() -> new JobApplication(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ApplicationStatus.REJECTED, now, NextAction.optional("Call", null), null, now, now, now, 0, null))
                .hasMessageContaining("terminal applications");
    }

    @Test
    void nextActionDueDateRequiresText() {
        assertThat(NextAction.optional(" ", null)).isNull();
        assertThatThrownBy(() -> NextAction.optional(" ", LocalDate.parse("2026-08-30")))
                .hasMessageContaining("requires nextActionText");
    }

    @Test
    void statusHistoryEnforcesInitialDraftAndTransitionRules() {
        ApplicationStatusHistory initial = new ApplicationStatusHistory(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, ApplicationStatus.DRAFT, now, null, now);
        assertThat(initial.previousStatus()).isNull();

        assertThatThrownBy(() -> new ApplicationStatusHistory(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, ApplicationStatus.APPLIED, now, null, now)).hasMessageContaining("initial");
        assertThatThrownBy(() -> new ApplicationStatusHistory(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ApplicationStatus.DRAFT, ApplicationStatus.APPLIED, now, null, now)).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> new ApplicationStatusHistory(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ApplicationStatus.INTERVIEWING, ApplicationStatus.INTERVIEWING, now, null, now))
                .hasMessageContaining("require a note");
        assertThatThrownBy(() -> new ApplicationStatusHistory(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ApplicationStatus.APPLIED, ApplicationStatus.INTERVIEWING, now, "Interview scheduled",
                now.minusSeconds(1))).hasMessageContaining("recordedAt");
    }
}
