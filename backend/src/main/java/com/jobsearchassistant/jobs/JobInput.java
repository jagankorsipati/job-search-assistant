package com.jobsearchassistant.jobs;

import java.time.LocalDate;

record JobInput(
        String companyName,
        String jobTitle,
        String workLocation,
        String postingUrl,
        JobSourceType sourceType,
        EmploymentType employmentType,
        String externalPostingId,
        LocalDate datePosted) {
}
