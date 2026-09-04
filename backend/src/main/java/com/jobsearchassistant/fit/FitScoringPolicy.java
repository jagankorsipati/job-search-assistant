package com.jobsearchassistant.fit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class FitScoringPolicy {
    static final String VERSION = "DETERMINISTIC_FIT_V1";

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FULL_CREDIT = BigDecimal.ONE;
    private static final BigDecimal PARTIAL_CREDIT = new BigDecimal("0.5");
    private static final BigDecimal NO_CREDIT = BigDecimal.ZERO;
    private static final Map<RequirementImportance, Integer> WEIGHTS = Map.of(
            RequirementImportance.REQUIRED, 2,
            RequirementImportance.PREFERRED, 1,
            RequirementImportance.UNSPECIFIED, 1);
    private static final Map<RequirementAssessment, BigDecimal> CREDITS = Map.of(
            RequirementAssessment.DEMONSTRATED, FULL_CREDIT,
            RequirementAssessment.PARTIALLY_DEMONSTRATED, PARTIAL_CREDIT,
            RequirementAssessment.NOT_DEMONSTRATED, NO_CREDIT,
            RequirementAssessment.CONTRADICTED, NO_CREDIT,
            RequirementAssessment.CONFLICTING_EVIDENCE, NO_CREDIT,
            RequirementAssessment.UNASSESSED, NO_CREDIT);
    private static final Comparator<JobRequirement> REQUIREMENT_ORDER = Comparator
            .comparing(JobRequirement::importance, Comparator.comparingInt(FitScoringPolicy::importanceOrder))
            .thenComparing(JobRequirement::category, Comparator.comparingInt(RequirementCategory::ordinal))
            .thenComparing(JobRequirement::createdAt)
            .thenComparing(JobRequirement::id);

    FitAnalysisResult score(List<JobRequirement> requirements, List<CandidateEvidenceLink> links) {
        if (requirements == null || links == null) {
            throw new FitScoringException("requirements and links are required");
        }
        List<JobRequirement> orderedRequirements = requirements.stream()
                .peek(this::validateRequirement)
                .sorted(REQUIREMENT_ORDER)
                .toList();
        Map<UUID, JobRequirement> requirementById = new HashMap<>();
        for (JobRequirement requirement : orderedRequirements) {
            if (requirementById.put(requirement.id(), requirement) != null) {
                throw new FitScoringException("duplicate requirement input");
            }
        }
        List<CandidateEvidenceLink> validLinks = links.stream()
                .peek(this::validateLink)
                .sorted(Comparator.comparing(CandidateEvidenceLink::id))
                .toList();
        Map<UUID, CandidateEvidenceLink> uniqueLinksById = new HashMap<>();
        for (CandidateEvidenceLink link : validLinks) {
            CandidateEvidenceLink existing = uniqueLinksById.putIfAbsent(link.id(), link);
            if (existing != null && !existing.equals(link)) {
                throw new FitScoringException("duplicate evidence link identity has conflicting input");
            }
            if (!requirementById.containsKey(link.jobRequirementId())) {
                throw new FitScoringException("evidence link references absent requirement");
            }
        }
        List<JobRequirement> confirmed = orderedRequirements.stream()
                .filter(requirement -> requirement.status() == RequirementStatus.CONFIRMED)
                .toList();
        int draftCount = countStatus(orderedRequirements, RequirementStatus.DRAFT);
        int rejectedCount = countStatus(orderedRequirements, RequirementStatus.REJECTED);
        if (confirmed.isEmpty()) {
            return new FitAnalysisResult(FitAnalysisStatus.NO_CONFIRMED_REQUIREMENTS, VERSION,
                    null, null, 0, draftCount, rejectedCount, emptyBreakdowns(),
                    List.of(), List.of(), List.of(), List.of());
        }

        Map<UUID, List<CandidateEvidenceLink>> linksByRequirement = uniqueLinksById.values().stream()
                .sorted(Comparator.comparing(CandidateEvidenceLink::id))
                .collect(Collectors.groupingBy(CandidateEvidenceLink::jobRequirementId));
        List<FitRequirementAssessment> assessments = confirmed.stream()
                .map(requirement -> assess(requirement, linksByRequirement.getOrDefault(requirement.id(), List.of())))
                .toList();
        int totalWeight = assessments.stream().mapToInt(FitRequirementAssessment::requirementWeight).sum();
        BigDecimal supportPoints = assessments.stream()
                .map(FitRequirementAssessment::weightedContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal assessedWeight = BigDecimal.valueOf(assessments.stream()
                .filter(assessment -> assessment.assessment() != RequirementAssessment.UNASSESSED)
                .mapToInt(FitRequirementAssessment::requirementWeight).sum());

        return new FitAnalysisResult(FitAnalysisStatus.SCORABLE, VERSION,
                score(supportPoints, totalWeight), score(assessedWeight, totalWeight),
                confirmed.size(), draftCount, rejectedCount, breakdowns(assessments),
                assessments, findings(assessments, Set.of(RequirementAssessment.NOT_DEMONSTRATED,
                        RequirementAssessment.UNASSESSED)),
                findings(assessments, Set.of(RequirementAssessment.PARTIALLY_DEMONSTRATED)),
                findings(assessments, Set.of(RequirementAssessment.CONTRADICTED,
                        RequirementAssessment.CONFLICTING_EVIDENCE)));
    }

    int weight(RequirementImportance importance) {
        Integer weight = WEIGHTS.get(importance);
        if (weight == null) {
            throw new FitScoringException("unknown requirement importance");
        }
        return weight;
    }

    BigDecimal credit(RequirementAssessment assessment) {
        BigDecimal credit = CREDITS.get(assessment);
        if (credit == null) {
            throw new FitScoringException("unknown requirement assessment");
        }
        return credit;
    }

    FitScore score(BigDecimal numerator, int denominator) {
        if (numerator == null || denominator <= 0) {
            throw new FitScoringException("score denominator must be positive");
        }
        int rounded = numerator.multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP)
                .intValueExact();
        return new FitScore(rounded, numerator.stripTrailingZeros(), denominator);
    }

    private FitRequirementAssessment assess(JobRequirement requirement, List<CandidateEvidenceLink> links) {
        EvidenceRelationshipCounts counts = counts(links);
        RequirementAssessment assessment;
        FitReasonCode reason;
        if (counts.total() == 0) {
            assessment = RequirementAssessment.UNASSESSED;
            reason = FitReasonCode.NO_LINKED_EVIDENCE;
        } else if (counts.contradicts() > 0 && (counts.supports() > 0 || counts.partiallySupports() > 0)) {
            assessment = RequirementAssessment.CONFLICTING_EVIDENCE;
            reason = FitReasonCode.CONFLICTING_SUPPORT_AND_CONTRADICTION;
        } else if (counts.contradicts() > 0) {
            assessment = RequirementAssessment.CONTRADICTED;
            reason = FitReasonCode.CONTRADICTING_EVIDENCE;
        } else if (counts.supports() > 0) {
            assessment = RequirementAssessment.DEMONSTRATED;
            reason = FitReasonCode.SUPPORTING_EVIDENCE;
        } else if (counts.partiallySupports() > 0) {
            assessment = RequirementAssessment.PARTIALLY_DEMONSTRATED;
            reason = FitReasonCode.PARTIAL_SUPPORTING_EVIDENCE;
        } else if (counts.notDemonstrated() > 0) {
            assessment = RequirementAssessment.NOT_DEMONSTRATED;
            reason = FitReasonCode.ONLY_NOT_DEMONSTRATED_EVIDENCE;
        } else {
            throw new FitScoringException("unsupported evidence relationship combination");
        }
        int weight = weight(requirement.importance());
        BigDecimal credit = credit(assessment);
        return new FitRequirementAssessment(requirement.id(), requirement.category(), requirement.importance(),
                assessment, weight, credit, credit.multiply(BigDecimal.valueOf(weight)).stripTrailingZeros(),
                counts, reason, requirement, links);
    }

    private EvidenceRelationshipCounts counts(List<CandidateEvidenceLink> links) {
        int supports = 0;
        int partial = 0;
        int contradicts = 0;
        int notDemonstrated = 0;
        for (CandidateEvidenceLink link : links) {
            switch (link.relationship()) {
                case SUPPORTS -> supports++;
                case PARTIALLY_SUPPORTS -> partial++;
                case CONTRADICTS -> contradicts++;
                case NOT_DEMONSTRATED -> notDemonstrated++;
            }
        }
        return new EvidenceRelationshipCounts(supports, partial, contradicts, notDemonstrated);
    }

    private Map<RequirementImportance, FitImportanceBreakdown> breakdowns(List<FitRequirementAssessment> assessments) {
        Map<RequirementImportance, FitImportanceBreakdown> result = new EnumMap<>(RequirementImportance.class);
        for (RequirementImportance importance : RequirementImportance.values()) {
            List<FitRequirementAssessment> group = assessments.stream()
                    .filter(assessment -> assessment.importance() == importance)
                    .toList();
            result.put(importance, breakdown(importance, group));
        }
        return result;
    }

    private FitImportanceBreakdown breakdown(RequirementImportance importance, List<FitRequirementAssessment> group) {
        if (group.isEmpty()) {
            return new FitImportanceBreakdown(importance, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
        int totalWeight = group.stream().mapToInt(FitRequirementAssessment::requirementWeight).sum();
        BigDecimal support = group.stream().map(FitRequirementAssessment::weightedContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal coverage = BigDecimal.valueOf(group.stream()
                .filter(assessment -> assessment.assessment() != RequirementAssessment.UNASSESSED)
                .mapToInt(FitRequirementAssessment::requirementWeight).sum());
        return new FitImportanceBreakdown(importance, true, group.size(), totalWeight,
                countAssessed(group), count(group, RequirementAssessment.DEMONSTRATED),
                count(group, RequirementAssessment.PARTIALLY_DEMONSTRATED),
                count(group, RequirementAssessment.NOT_DEMONSTRATED),
                count(group, RequirementAssessment.CONTRADICTED),
                count(group, RequirementAssessment.CONFLICTING_EVIDENCE),
                count(group, RequirementAssessment.UNASSESSED),
                score(support, totalWeight), score(coverage, totalWeight));
    }

    private Map<RequirementImportance, FitImportanceBreakdown> emptyBreakdowns() {
        Map<RequirementImportance, FitImportanceBreakdown> result = new EnumMap<>(RequirementImportance.class);
        for (RequirementImportance importance : RequirementImportance.values()) {
            result.put(importance, breakdown(importance, List.of()));
        }
        return result;
    }

    private List<FitFinding> findings(List<FitRequirementAssessment> assessments, Set<RequirementAssessment> states) {
        List<FitFinding> result = new ArrayList<>();
        for (FitRequirementAssessment assessment : assessments) {
            if (states.contains(assessment.assessment())) {
                result.add(FitFinding.from(assessment));
            }
        }
        return result;
    }

    private int countStatus(List<JobRequirement> requirements, RequirementStatus status) {
        return (int) requirements.stream().filter(requirement -> requirement.status() == status).count();
    }

    private int countAssessed(List<FitRequirementAssessment> assessments) {
        return (int) assessments.stream()
                .filter(assessment -> assessment.assessment() != RequirementAssessment.UNASSESSED)
                .count();
    }

    private int count(List<FitRequirementAssessment> assessments, RequirementAssessment status) {
        return (int) assessments.stream().filter(assessment -> assessment.assessment() == status).count();
    }

    private void validateRequirement(JobRequirement requirement) {
        if (requirement == null || requirement.id() == null || requirement.ownerAccountId() == null
                || requirement.jobId() == null || requirement.jobSnapshotId() == null
                || requirement.category() == null || requirement.importance() == null || requirement.status() == null
                || requirement.createdAt() == null) {
            throw new FitScoringException("malformed requirement input");
        }
    }

    private void validateLink(CandidateEvidenceLink link) {
        if (link == null || link.id() == null || link.ownerAccountId() == null || link.jobRequirementId() == null
                || link.evidenceType() == null || link.evidenceId() == null || link.relationship() == null) {
            throw new FitScoringException("malformed evidence link input");
        }
    }

    private static int importanceOrder(RequirementImportance importance) {
        return switch (importance) {
            case REQUIRED -> 0;
            case PREFERRED -> 1;
            case UNSPECIFIED -> 2;
        };
    }
}
