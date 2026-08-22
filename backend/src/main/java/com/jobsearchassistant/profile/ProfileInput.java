package com.jobsearchassistant.profile;

import java.time.LocalDate;

record ProfileInput(
        String professionalDisplayName,
        String professionalHeadline,
        String careerSummary,
        String locationPreference,
        String targetRoles,
        String workAuthorizationStatement,
        String workLocationPreferences) {
}

record CareerFactInput(
        CareerFactCategory category,
        String factualContent,
        String organization,
        String title,
        String location,
        LocalDate startedOn,
        LocalDate endedOn,
        Boolean ongoing) {
}
