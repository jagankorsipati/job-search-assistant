package com.jobsearchassistant.fit;

record EvidenceRelationshipCounts(
        int supports,
        int partiallySupports,
        int contradicts,
        int notDemonstrated) {
    int total() {
        return supports + partiallySupports + contradicts + notDemonstrated;
    }
}
