package com.jobsearchassistant.fit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

record FitRequirementAssessment(
        UUID requirementId,
        RequirementCategory category,
        RequirementImportance importance,
        RequirementAssessment assessment,
        int requirementWeight,
        BigDecimal evidenceCredit,
        BigDecimal weightedContribution,
        EvidenceRelationshipCounts evidenceRelationshipCounts,
        FitReasonCode reasonCode,
        JobRequirement requirement,
        List<CandidateEvidenceLink> evidenceLinks) {
    FitRequirementAssessment {
        evidenceLinks = List.copyOf(evidenceLinks);
    }
}
