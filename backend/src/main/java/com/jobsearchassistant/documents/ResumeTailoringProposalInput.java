package com.jobsearchassistant.documents;

import java.util.List;

record ResumeTailoringProposalInput(
        ResumeTailoringTargetSection targetSection,
        String targetReference,
        String originalText,
        String proposedText,
        List<ResumeTailoringEvidenceInput> evidence) {
}
