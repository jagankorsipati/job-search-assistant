package com.jobsearchassistant.fit;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jobsearchassistant.identity.api.UnauthenticatedActorException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class FitController {
    private final FitService service;

    FitController(FitService service) {
        this.service = service;
    }

    @GetMapping("/jobs/{jobId}/snapshots/{snapshotId}/requirements")
    ResponseEntity<List<RequirementResponse>> listRequirements(
            @PathVariable UUID jobId,
            @PathVariable UUID snapshotId,
            @RequestParam(required = false) Integer limit) {
        return ok(service.listRequirements(jobId, snapshotId, limit).stream().map(RequirementResponse::from).toList());
    }

    @GetMapping("/jobs/{jobId}/snapshots/{snapshotId}/fit-analysis")
    ResponseEntity<FitAnalysisResponse> analyzeFit(@PathVariable UUID jobId, @PathVariable UUID snapshotId) {
        return ok(FitAnalysisResponse.from(jobId, snapshotId, service.analyzeSnapshot(jobId, snapshotId)));
    }

    @PostMapping("/jobs/{jobId}/snapshots/{snapshotId}/requirements")
    ResponseEntity<RequirementResponse> createRequirement(
            @PathVariable UUID jobId,
            @PathVariable UUID snapshotId,
            @RequestBody RequirementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(RequirementResponse.from(service.createRequirement(jobId, snapshotId, request.toInput())));
    }

    @GetMapping("/job-requirements/{requirementId}")
    ResponseEntity<RequirementResponse> getRequirement(@PathVariable UUID requirementId) {
        return ok(RequirementResponse.from(service.getRequirement(requirementId)));
    }

    @PutMapping("/job-requirements/{requirementId}")
    ResponseEntity<RequirementResponse> updateRequirement(
            @PathVariable UUID requirementId,
            @RequestBody RequirementUpdateRequest request) {
        return ok(RequirementResponse.from(service.updateRequirement(
                requirementId, request.toInput(), request.expectedVersionValue())));
    }

    @DeleteMapping("/job-requirements/{requirementId}")
    ResponseEntity<Void> deleteRequirement(@PathVariable UUID requirementId, @RequestBody VersionedRequest request) {
        service.deleteRequirement(requirementId, request.expectedVersionValue());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/job-requirements/{requirementId}/evidence-links")
    ResponseEntity<List<EvidenceLinkResponse>> listEvidenceLinks(
            @PathVariable UUID requirementId,
            @RequestParam(required = false) Integer limit) {
        return ok(service.listEvidenceLinks(requirementId, limit).stream().map(EvidenceLinkResponse::from).toList());
    }

    @PostMapping("/job-requirements/{requirementId}/evidence-links")
    ResponseEntity<EvidenceLinkResponse> createEvidenceLink(
            @PathVariable UUID requirementId,
            @RequestBody EvidenceLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(EvidenceLinkResponse.from(service.createEvidenceLink(requirementId, request.toInput())));
    }

    @PutMapping("/job-requirement-evidence/{linkId}")
    ResponseEntity<EvidenceLinkResponse> updateEvidenceLink(
            @PathVariable UUID linkId,
            @RequestBody EvidenceLinkUpdateRequest request) {
        return ok(EvidenceLinkResponse.from(service.updateEvidenceLink(
                linkId, request.toInput(), request.expectedVersionValue())));
    }

    @DeleteMapping("/job-requirement-evidence/{linkId}")
    ResponseEntity<Void> deleteEvidenceLink(@PathVariable UUID linkId, @RequestBody VersionedRequest request) {
        service.deleteEvidenceLink(linkId, request.expectedVersionValue());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(FitNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "not_found");
    }

    @ExceptionHandler(FitConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(FitConflictException conflict) {
        return problem(HttpStatus.CONFLICT, "Fit operation conflict", conflict.getMessage());
    }

    @ExceptionHandler(FitAnalysisTooLargeException.class)
    ResponseEntity<Map<String, Object>> analysisTooLarge() {
        return problem(HttpStatus.CONFLICT, "Fit analysis is too large", "analysis_too_large");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class, NullPointerException.class})
    ResponseEntity<Map<String, Object>> badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid fit request", "invalid_request");
    }

    @ExceptionHandler(UnauthenticatedActorException.class)
    ResponseEntity<Map<String, Object>> unauthorized() {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication required", "authentication_required");
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String title, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("title", title, "status", status.value(), "code", code));
    }

    record RequirementRequest(
            RequirementCategory category,
            RequirementImportance importance,
            String requirementText,
            String sourceExcerpt,
            RequirementStatus status) {
        RequirementInput toInput() {
            return new RequirementInput(category, importance, requirementText, sourceExcerpt, status);
        }
    }

    record RequirementUpdateRequest(
            RequirementCategory category,
            RequirementImportance importance,
            String requirementText,
            String sourceExcerpt,
            RequirementStatus status,
            Long expectedVersion) {
        RequirementInput toInput() {
            return new RequirementInput(category, importance, requirementText, sourceExcerpt, status);
        }
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record EvidenceLinkRequest(
            EvidenceType evidenceType,
            UUID evidenceId,
            EvidenceRelationship relationship,
            String userNote) {
        EvidenceLinkInput toInput() {
            return new EvidenceLinkInput(evidenceType, evidenceId, relationship, userNote);
        }
    }

    record EvidenceLinkUpdateRequest(
            EvidenceType evidenceType,
            UUID evidenceId,
            EvidenceRelationship relationship,
            String userNote,
            Long expectedVersion) {
        EvidenceLinkInput toInput() {
            return new EvidenceLinkInput(evidenceType, evidenceId, relationship, userNote);
        }
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record VersionedRequest(Long expectedVersion) {
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record RequirementResponse(
            UUID id,
            UUID jobId,
            UUID jobSnapshotId,
            RequirementCategory category,
            RequirementImportance importance,
            String requirementText,
            String sourceExcerpt,
            RequirementStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        static RequirementResponse from(JobRequirement requirement) {
            return new RequirementResponse(requirement.id(), requirement.jobId(), requirement.jobSnapshotId(),
                    requirement.category(), requirement.importance(), requirement.requirementText(),
                    requirement.sourceExcerpt(), requirement.status(), requirement.createdAt(),
                    requirement.updatedAt(), requirement.version());
        }
    }

    record EvidenceLinkResponse(
            UUID id,
            UUID jobRequirementId,
            EvidenceType evidenceType,
            UUID evidenceId,
            EvidenceRelationship relationship,
            String userNote,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        static EvidenceLinkResponse from(CandidateEvidenceLink link) {
            return new EvidenceLinkResponse(link.id(), link.jobRequirementId(), link.evidenceType(),
                    link.evidenceId(), link.relationship(), link.userNote(), link.createdAt(),
                    link.updatedAt(), link.version());
        }
    }

    record FitAnalysisResponse(
            String policyVersion,
            FitAnalysisStatus analysisStatus,
            UUID jobId,
            UUID snapshotId,
            Integer evidenceSupportScore,
            Integer evidenceCoverageScore,
            int confirmedRequirementCount,
            int draftRequirementCount,
            int rejectedRequirementCount,
            int totalEligibleWeight,
            BigDecimal supportPoints,
            BigDecimal assessedWeight,
            List<ImportanceBreakdownResponse> importanceBreakdowns,
            List<RequirementAssessmentResponse> requirementAssessments,
            List<FindingResponse> gaps,
            List<FindingResponse> contradictions) {
        static FitAnalysisResponse from(UUID jobId, UUID snapshotId, FitAnalysisResult result) {
            List<RequirementAssessmentResponse> assessments = result.requirementAssessments().stream()
                    .map(RequirementAssessmentResponse::from)
                    .toList();
            int totalEligibleWeight = assessments.stream().mapToInt(RequirementAssessmentResponse::requirementWeight).sum();
            BigDecimal supportPoints = assessments.stream()
                    .map(RequirementAssessmentResponse::weightedContribution)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
            BigDecimal assessedWeight = BigDecimal.valueOf(assessments.stream()
                    .filter(assessment -> assessment.assessment() != RequirementAssessment.UNASSESSED)
                    .mapToInt(RequirementAssessmentResponse::requirementWeight).sum()).stripTrailingZeros();
            List<FindingResponse> gaps = new ArrayList<>();
            result.gaps().stream().map(FindingResponse::from).forEach(gaps::add);
            result.partialGaps().stream().map(FindingResponse::from).forEach(gaps::add);
            return new FitAnalysisResponse(result.policyVersion(), result.status(), jobId, snapshotId,
                    scoreValue(result.evidenceSupportScore()), scoreValue(result.evidenceCoverageScore()),
                    result.totalConfirmedRequirementCount(), result.draftRequirementCount(),
                    result.rejectedRequirementCount(), totalEligibleWeight, supportPoints, assessedWeight,
                    result.breakdowns().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(FitController::importanceOrder)))
                            .map(entry -> ImportanceBreakdownResponse.from(entry.getValue()))
                            .toList(),
                    assessments, gaps, result.contradictions().stream().map(FindingResponse::from).toList());
        }

        private static Integer scoreValue(FitScore score) {
            return score == null ? null : score.score();
        }
    }

    record ImportanceBreakdownResponse(
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
            Integer evidenceSupportScore,
            Integer evidenceCoverageScore) {
        static ImportanceBreakdownResponse from(FitImportanceBreakdown breakdown) {
            return new ImportanceBreakdownResponse(breakdown.importance(), breakdown.applicable(),
                    breakdown.confirmedRequirementCount(), breakdown.totalWeight(), breakdown.assessedCount(),
                    breakdown.demonstratedCount(), breakdown.partiallyDemonstratedCount(),
                    breakdown.notDemonstratedCount(), breakdown.contradictedCount(),
                    breakdown.conflictingEvidenceCount(), breakdown.unassessedCount(),
                    FitAnalysisResponse.scoreValue(breakdown.supportScore()),
                    FitAnalysisResponse.scoreValue(breakdown.coverageScore()));
        }
    }

    record RequirementAssessmentResponse(
            UUID requirementId,
            RequirementCategory requirementCategory,
            RequirementImportance importance,
            RequirementStatus requirementStatus,
            String requirementText,
            String sourceExcerpt,
            RequirementAssessment assessment,
            FitReasonCode reasonCode,
            int requirementWeight,
            BigDecimal evidenceCredit,
            BigDecimal weightedContribution,
            EvidenceRelationshipCounts evidenceRelationshipCounts,
            List<EvidenceLinkSummaryResponse> evidenceLinks) {
        static RequirementAssessmentResponse from(FitRequirementAssessment assessment) {
            return new RequirementAssessmentResponse(assessment.requirement().id(), assessment.requirement().category(),
                    assessment.requirement().importance(), assessment.requirement().status(),
                    assessment.requirement().requirementText(), assessment.requirement().sourceExcerpt(),
                    assessment.assessment(), assessment.reasonCode(), assessment.requirementWeight(),
                    assessment.evidenceCredit(), assessment.weightedContribution(),
                    assessment.evidenceRelationshipCounts(), assessment.evidenceLinks().stream()
                            .sorted(Comparator.comparing(CandidateEvidenceLink::relationship,
                                            Comparator.comparingInt(EvidenceRelationship::ordinal))
                                    .thenComparing(CandidateEvidenceLink::evidenceType,
                                            Comparator.comparingInt(EvidenceType::ordinal))
                                    .thenComparing(CandidateEvidenceLink::createdAt)
                                    .thenComparing(CandidateEvidenceLink::id))
                            .map(EvidenceLinkSummaryResponse::from)
                            .toList());
        }
    }

    record EvidenceLinkSummaryResponse(
            UUID evidenceLinkId,
            EvidenceType evidenceType,
            UUID evidenceId,
            EvidenceRelationship relationship,
            String userNote,
            String evidenceReference) {
        static EvidenceLinkSummaryResponse from(CandidateEvidenceLink link) {
            return new EvidenceLinkSummaryResponse(link.id(), link.evidenceType(), link.evidenceId(),
                    link.relationship(), link.userNote(), link.evidenceType().name());
        }
    }

    record FindingResponse(
            FitFindingType findingType,
            UUID requirementId,
            RequirementCategory requirementCategory,
            RequirementImportance importance,
            RequirementAssessment assessment,
            FitReasonCode reasonCode) {
        static FindingResponse from(FitFinding finding) {
            return new FindingResponse(FitFindingType.from(finding.assessment()), finding.requirementId(),
                    finding.category(), finding.importance(), finding.assessment(), finding.reasonCode());
        }
    }

    enum FitFindingType {
        UNASSESSED_REQUIREMENT,
        EVIDENCE_NOT_DEMONSTRATED,
        PARTIAL_EVIDENCE,
        CONTRADICTING_EVIDENCE,
        CONFLICTING_EVIDENCE;

        static FitFindingType from(RequirementAssessment assessment) {
            return switch (assessment) {
                case UNASSESSED -> UNASSESSED_REQUIREMENT;
                case NOT_DEMONSTRATED -> EVIDENCE_NOT_DEMONSTRATED;
                case PARTIALLY_DEMONSTRATED -> PARTIAL_EVIDENCE;
                case CONTRADICTED -> CONTRADICTING_EVIDENCE;
                case CONFLICTING_EVIDENCE -> CONFLICTING_EVIDENCE;
                case DEMONSTRATED -> throw new IllegalArgumentException("demonstrated findings are unsupported");
            };
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
