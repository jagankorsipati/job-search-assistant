package com.jobsearchassistant.fit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class FitScoringPolicyTests {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID JOB = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SNAPSHOT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant START = Instant.parse("2026-09-03T12:00:00Z");

    private final FitScoringPolicy policy = new FitScoringPolicy();

    @Test
    void noConfirmedRequirementsProducesNonScorableResultWithVisibleExclusions() {
        FitAnalysisResult result = policy.score(List.of(
                requirement(1, RequirementImportance.REQUIRED, RequirementStatus.DRAFT),
                requirement(2, RequirementImportance.PREFERRED, RequirementStatus.REJECTED)), List.of());

        assertThat(result.status()).isEqualTo(FitAnalysisStatus.NO_CONFIRMED_REQUIREMENTS);
        assertThat(result.evidenceSupportScore()).isNull();
        assertThat(result.evidenceCoverageScore()).isNull();
        assertThat(result.draftRequirementCount()).isEqualTo(1);
        assertThat(result.rejectedRequirementCount()).isEqualTo(1);
        assertThat(result.breakdowns().values()).allMatch(breakdown -> !breakdown.applicable());
    }

    @Test
    void singleRequiredRequirementAssessmentsHaveExpectedScoresAndCoverage() {
        assertSingle(EvidenceRelationship.SUPPORTS, RequirementAssessment.DEMONSTRATED, 100, 100);
        assertSingle(EvidenceRelationship.PARTIALLY_SUPPORTS, RequirementAssessment.PARTIALLY_DEMONSTRATED, 50, 100);
        assertSingle(EvidenceRelationship.NOT_DEMONSTRATED, RequirementAssessment.NOT_DEMONSTRATED, 0, 100);
        assertSingle(EvidenceRelationship.CONTRADICTS, RequirementAssessment.CONTRADICTED, 0, 100);

        JobRequirement requirement = requirement(5, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        FitAnalysisResult unassessed = policy.score(List.of(requirement), List.of());
        assertThat(unassessed.requirementAssessments().getFirst().assessment()).isEqualTo(RequirementAssessment.UNASSESSED);
        assertThat(unassessed.evidenceSupportScore().score()).isZero();
        assertThat(unassessed.evidenceCoverageScore().score()).isZero();
    }

    @Test
    void relationshipPrecedenceIsConservativeAndDoesNotInflateMultipleLinks() {
        JobRequirement supportsAndContradicts = requirement(1, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        assertThat(policy.score(List.of(supportsAndContradicts), links(supportsAndContradicts,
                EvidenceRelationship.SUPPORTS, EvidenceRelationship.CONTRADICTS))
                .requirementAssessments().getFirst().assessment()).isEqualTo(RequirementAssessment.CONFLICTING_EVIDENCE);

        JobRequirement partialAndContradicts = requirement(2, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        assertThat(policy.score(List.of(partialAndContradicts), links(partialAndContradicts,
                EvidenceRelationship.PARTIALLY_SUPPORTS, EvidenceRelationship.CONTRADICTS))
                .requirementAssessments().getFirst().assessment()).isEqualTo(RequirementAssessment.CONFLICTING_EVIDENCE);

        JobRequirement supportAndNotDemonstrated = requirement(3, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        assertThat(policy.score(List.of(supportAndNotDemonstrated), links(supportAndNotDemonstrated,
                EvidenceRelationship.SUPPORTS, EvidenceRelationship.NOT_DEMONSTRATED))
                .requirementAssessments().getFirst().assessment()).isEqualTo(RequirementAssessment.DEMONSTRATED);

        JobRequirement partialAndNotDemonstrated = requirement(4, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        assertThat(policy.score(List.of(partialAndNotDemonstrated), links(partialAndNotDemonstrated,
                EvidenceRelationship.PARTIALLY_SUPPORTS, EvidenceRelationship.NOT_DEMONSTRATED))
                .requirementAssessments().getFirst().assessment()).isEqualTo(RequirementAssessment.PARTIALLY_DEMONSTRATED);

        JobRequirement repeatedAbsence = requirement(5, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        assertThat(policy.score(List.of(repeatedAbsence), links(repeatedAbsence,
                EvidenceRelationship.NOT_DEMONSTRATED, EvidenceRelationship.NOT_DEMONSTRATED))
                .requirementAssessments().getFirst().assessment()).isEqualTo(RequirementAssessment.NOT_DEMONSTRATED);

        JobRequirement repeatedSupport = requirement(6, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        FitAnalysisResult result = policy.score(List.of(repeatedSupport), links(repeatedSupport,
                EvidenceRelationship.SUPPORTS, EvidenceRelationship.SUPPORTS, EvidenceRelationship.SUPPORTS));
        assertThat(result.evidenceSupportScore().numerator()).isEqualByComparingTo("2");
        assertThat(result.evidenceSupportScore().score()).isEqualTo(100);
    }

    @Test
    void weightingRoundingAndBreakdownsRemainExplainable() {
        JobRequirement required = requirement(1, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        JobRequirement preferred = requirement(2, RequirementImportance.PREFERRED, RequirementStatus.CONFIRMED);
        FitAnalysisResult result = policy.score(List.of(preferred, required), links(required, EvidenceRelationship.SUPPORTS));

        assertThat(result.evidenceSupportScore()).isEqualTo(new FitScore(67, new BigDecimal("2"), 3));
        assertThat(result.evidenceCoverageScore()).isEqualTo(new FitScore(67, new BigDecimal("2"), 3));
        assertThat(result.breakdowns().get(RequirementImportance.REQUIRED).supportScore().score()).isEqualTo(100);
        assertThat(result.breakdowns().get(RequirementImportance.PREFERRED).supportScore().score()).isZero();
        assertThat(result.breakdowns().get(RequirementImportance.UNSPECIFIED).applicable()).isFalse();
    }

    @Test
    void mixedAssessmentListsGapsRisksAndAllReasonCodes() {
        JobRequirement demonstrated = requirement(1, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        JobRequirement partial = requirement(2, RequirementImportance.PREFERRED, RequirementStatus.CONFIRMED);
        JobRequirement notDemonstrated = requirement(3, RequirementImportance.UNSPECIFIED, RequirementStatus.CONFIRMED);
        JobRequirement contradicted = requirement(4, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        JobRequirement conflicting = requirement(5, RequirementImportance.PREFERRED, RequirementStatus.CONFIRMED);
        JobRequirement unassessed = requirement(6, RequirementImportance.UNSPECIFIED, RequirementStatus.CONFIRMED);
        FitAnalysisResult result = policy.score(List.of(unassessed, conflicting, contradicted, notDemonstrated, partial, demonstrated),
                concat(links(demonstrated, EvidenceRelationship.SUPPORTS),
                        links(partial, EvidenceRelationship.PARTIALLY_SUPPORTS),
                        links(notDemonstrated, EvidenceRelationship.NOT_DEMONSTRATED),
                        links(contradicted, EvidenceRelationship.CONTRADICTS),
                        links(conflicting, EvidenceRelationship.SUPPORTS, EvidenceRelationship.CONTRADICTS)));

        assertThat(result.evidenceSupportScore()).isEqualTo(new FitScore(31, new BigDecimal("2.5"), 8));
        assertThat(result.evidenceCoverageScore()).isEqualTo(new FitScore(88, new BigDecimal("7"), 8));
        assertThat(result.gaps()).extracting(FitFinding::assessment)
                .containsExactly(RequirementAssessment.NOT_DEMONSTRATED, RequirementAssessment.UNASSESSED);
        assertThat(result.partialGaps()).extracting(FitFinding::assessment)
                .containsExactly(RequirementAssessment.PARTIALLY_DEMONSTRATED);
        assertThat(result.contradictions()).extracting(FitFinding::assessment)
                .containsExactly(RequirementAssessment.CONTRADICTED, RequirementAssessment.CONFLICTING_EVIDENCE);
        assertThat(result.requirementAssessments()).extracting(FitRequirementAssessment::reasonCode)
                .contains(FitReasonCode.SUPPORTING_EVIDENCE, FitReasonCode.PARTIAL_SUPPORTING_EVIDENCE,
                        FitReasonCode.ONLY_NOT_DEMONSTRATED_EVIDENCE, FitReasonCode.CONTRADICTING_EVIDENCE,
                        FitReasonCode.CONFLICTING_SUPPORT_AND_CONTRADICTION, FitReasonCode.NO_LINKED_EVIDENCE);
    }

    @Test
    void halfUpRoundingPolicyIsCentralized() {
        assertThat(policy.score(new BigDecimal("0.5"), 100).score()).isEqualTo(1);
        assertThat(policy.score(new BigDecimal("0.49"), 100).score()).isZero();
    }

    @Test
    void shufflingInputsProducesIdenticalResultAndStableOrdering() {
        JobRequirement lateRequired = requirement(20, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        JobRequirement earlyRequired = requirement(10, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        JobRequirement preferred = requirement(1, RequirementImportance.PREFERRED, RequirementStatus.CONFIRMED);
        List<JobRequirement> requirements = new ArrayList<>(List.of(preferred, lateRequired, earlyRequired));
        List<CandidateEvidenceLink> links = new ArrayList<>(concat(
                links(preferred, EvidenceRelationship.NOT_DEMONSTRATED),
                links(lateRequired, EvidenceRelationship.PARTIALLY_SUPPORTS),
                links(earlyRequired, EvidenceRelationship.SUPPORTS)));

        FitAnalysisResult first = policy.score(requirements, links);
        Collections.shuffle(requirements, new java.util.Random(7));
        Collections.shuffle(links, new java.util.Random(11));
        FitAnalysisResult second = policy.score(requirements, links);

        assertThat(second).isEqualTo(first);
        assertThat(first.requirementAssessments()).extracting(FitRequirementAssessment::requirementId)
                .containsExactly(earlyRequired.id(), lateRequired.id(), preferred.id());
    }

    @Test
    void duplicateLinkObjectsAreDeduplicatedByLinkIdentity() {
        JobRequirement requirement = requirement(1, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        CandidateEvidenceLink link = link(requirement, 1, EvidenceRelationship.SUPPORTS);

        FitAnalysisResult result = policy.score(List.of(requirement), List.of(link, link));

        assertThat(result.requirementAssessments().getFirst().evidenceRelationshipCounts().supports()).isEqualTo(1);
        assertThat(result.evidenceSupportScore().numerator()).isEqualByComparingTo("2");
    }

    @Test
    void duplicateLinkIdentityWithConflictingContentsFailsClosed() {
        JobRequirement requirement = requirement(1, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        CandidateEvidenceLink supports = link(requirement, 1, EvidenceRelationship.SUPPORTS);
        CandidateEvidenceLink contradicts = new CandidateEvidenceLink(supports.id(), OWNER, requirement.id(),
                EvidenceType.CAREER_FACT, supports.evidenceId(), EvidenceRelationship.CONTRADICTS, null, START, START, 0);

        assertThatThrownBy(() -> policy.score(List.of(requirement), List.of(supports, contradicts)))
                .isInstanceOf(FitScoringException.class)
                .hasMessage("duplicate evidence link identity has conflicting input");
    }

    @Test
    void absentRequirementReferencesAndMalformedInputsFailClosed() {
        JobRequirement requirement = requirement(1, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        JobRequirement absent = requirement(2, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);

        assertThatThrownBy(() -> policy.score(List.of(requirement), links(absent, EvidenceRelationship.SUPPORTS)))
                .isInstanceOf(FitScoringException.class)
                .hasMessage("evidence link references absent requirement");
        assertThatThrownBy(() -> policy.score(List.of(requirement, requirement), List.of()))
                .isInstanceOf(FitScoringException.class)
                .hasMessage("duplicate requirement input");
        assertThatThrownBy(() -> policy.score(List.of(malformedRequirement()), List.of()))
                .isInstanceOf(FitScoringException.class)
                .hasMessage("malformed requirement input");
        assertThatThrownBy(() -> policy.score(List.of(requirement), List.of(malformedLink(requirement))))
                .isInstanceOf(FitScoringException.class)
                .hasMessage("malformed evidence link input");
    }

    @Test
    void largeBoundedInputCompletesDeterministically() {
        List<JobRequirement> requirements = IntStream.rangeClosed(1, 100)
                .mapToObj(i -> requirement(i, i % 3 == 0 ? RequirementImportance.PREFERRED : RequirementImportance.REQUIRED,
                        RequirementStatus.CONFIRMED))
                .toList();
        List<CandidateEvidenceLink> links = requirements.stream()
                .flatMap(requirement -> links(requirement, EvidenceRelationship.SUPPORTS).stream())
                .toList();

        FitAnalysisResult result = policy.score(requirements, links);

        assertThat(result.totalConfirmedRequirementCount()).isEqualTo(100);
        assertThat(result.evidenceSupportScore().score()).isEqualTo(100);
        assertThat(result.policyVersion()).isEqualTo(FitScoringPolicy.VERSION);
    }

    private void assertSingle(EvidenceRelationship relationship, RequirementAssessment assessment, int support, int coverage) {
        JobRequirement requirement = requirement(1, RequirementImportance.REQUIRED, RequirementStatus.CONFIRMED);
        FitAnalysisResult result = policy.score(List.of(requirement), links(requirement, relationship));
        assertThat(result.status()).isEqualTo(FitAnalysisStatus.SCORABLE);
        assertThat(result.requirementAssessments().getFirst().assessment()).isEqualTo(assessment);
        assertThat(result.evidenceSupportScore().score()).isEqualTo(support);
        assertThat(result.evidenceCoverageScore().score()).isEqualTo(coverage);
    }

    private JobRequirement requirement(int index, RequirementImportance importance, RequirementStatus status) {
        RequirementCategory category = RequirementCategory.values()[Math.floorMod(index, RequirementCategory.values().length)];
        return new JobRequirement(uuid(10, index), OWNER, JOB, SNAPSHOT, category, importance,
                "Requirement " + index, null, status, START.plusSeconds(index), START.plusSeconds(index), 0);
    }

    private JobRequirement malformedRequirement() {
        return new JobRequirement(uuid(10, 999), OWNER, JOB, SNAPSHOT, null, RequirementImportance.REQUIRED,
                "Requirement", null, RequirementStatus.CONFIRMED, START, START, 0);
    }

    private List<CandidateEvidenceLink> links(JobRequirement requirement, EvidenceRelationship... relationships) {
        List<CandidateEvidenceLink> links = new ArrayList<>();
        for (int i = 0; i < relationships.length; i++) {
            links.add(link(requirement, i + 1, relationships[i]));
        }
        return links;
    }

    private CandidateEvidenceLink link(JobRequirement requirement, int index, EvidenceRelationship relationship) {
        return new CandidateEvidenceLink(uuid(requirement.id().getLeastSignificantBits(), index), OWNER, requirement.id(),
                EvidenceType.CAREER_FACT, uuid(80, index), relationship, null, START, START, 0);
    }

    private CandidateEvidenceLink malformedLink(JobRequirement requirement) {
        return new CandidateEvidenceLink(uuid(90, 1), OWNER, requirement.id(), EvidenceType.CAREER_FACT,
                uuid(91, 1), null, null, START, START, 0);
    }

    @SafeVarargs
    private final List<CandidateEvidenceLink> concat(List<CandidateEvidenceLink>... parts) {
        List<CandidateEvidenceLink> result = new ArrayList<>();
        for (List<CandidateEvidenceLink> part : parts) {
            result.addAll(part);
        }
        return result;
    }

    private UUID uuid(long high, long low) {
        return new UUID(high, low);
    }
}
