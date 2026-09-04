package com.jobsearchassistant.fit;

import java.util.List;
import java.util.Map;

record FitAnalysisResult(
        FitAnalysisStatus status,
        String policyVersion,
        FitScore evidenceSupportScore,
        FitScore evidenceCoverageScore,
        int totalConfirmedRequirementCount,
        int draftRequirementCount,
        int rejectedRequirementCount,
        Map<RequirementImportance, FitImportanceBreakdown> breakdowns,
        List<FitRequirementAssessment> requirementAssessments,
        List<FitFinding> gaps,
        List<FitFinding> partialGaps,
        List<FitFinding> contradictions) {
    FitAnalysisResult {
        breakdowns = Map.copyOf(breakdowns);
        requirementAssessments = List.copyOf(requirementAssessments);
        gaps = List.copyOf(gaps);
        partialGaps = List.copyOf(partialGaps);
        contradictions = List.copyOf(contradictions);
    }
}
