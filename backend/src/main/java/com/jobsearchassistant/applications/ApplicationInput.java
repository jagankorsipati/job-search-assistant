package com.jobsearchassistant.applications;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record ApplicationInput(
        UUID jobId,
        String privateNotes,
        String nextActionText,
        LocalDate nextActionDueDate) {
}

record ApplicationUpdateInput(String privateNotes, String nextActionText, LocalDate nextActionDueDate) {
}

record ApplicationTransitionInput(ApplicationStatus targetStatus, Instant appliedAt, String note) {
}
