package com.jobsearchassistant.documents;

import java.util.List;

record ResumeTailoringApprovalEligibility(boolean eligible, List<String> reasons) {
    ResumeTailoringApprovalEligibility {
        reasons = List.copyOf(reasons);
    }
}
