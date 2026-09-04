package com.jobsearchassistant.fit;

import java.util.UUID;

record FitFinding(
        UUID requirementId,
        RequirementCategory category,
        RequirementImportance importance,
        RequirementAssessment assessment,
        FitReasonCode reasonCode) {
    static FitFinding from(FitRequirementAssessment assessment) {
        return new FitFinding(assessment.requirementId(), assessment.category(), assessment.importance(),
                assessment.assessment(), assessment.reasonCode());
    }
}
