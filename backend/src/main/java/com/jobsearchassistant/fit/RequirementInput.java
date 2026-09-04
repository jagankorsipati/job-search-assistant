package com.jobsearchassistant.fit;

record RequirementInput(
        RequirementCategory category,
        RequirementImportance importance,
        String requirementText,
        String sourceExcerpt,
        RequirementStatus status) {
}
