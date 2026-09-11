package com.jobsearchassistant.documents;

import java.util.List;

record ResumeTailoringReview(
        ResumeTailoringProposal proposal,
        ResumeTailoringApprovalEligibility eligibility,
        String reviewToken,
        List<ResumeTailoringFactReference> evidenceReferences) {
    ResumeTailoringReview {
        evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
    }
}
