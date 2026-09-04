package com.jobsearchassistant.fit;

record FitImportanceBreakdown(
        RequirementImportance importance,
        boolean applicable,
        int confirmedRequirementCount,
        int totalWeight,
        int assessedCount,
        int demonstratedCount,
        int partiallyDemonstratedCount,
        int notDemonstratedCount,
        int contradictedCount,
        int conflictingEvidenceCount,
        int unassessedCount,
        FitScore supportScore,
        FitScore coverageScore) {
}
