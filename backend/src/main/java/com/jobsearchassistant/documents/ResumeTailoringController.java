package com.jobsearchassistant.documents;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@RequestMapping("/api/documents/resume-tailoring-proposals")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ResumeTailoringController {
    private final ResumeTailoringService service;

    ResumeTailoringController(ResumeTailoringService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<List<ProposalResponse>> list(@RequestParam(required = false) Integer limit) {
        return ok(service.listDrafts(limit).stream().map(ProposalResponse::from).toList());
    }

    @PostMapping
    ResponseEntity<ProposalResponse> create(@RequestBody CreateProposalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ProposalResponse.from(service.createDraft(request.sourceResumeDocumentId(),
                        request.sourceResumeVersionValue(), request.sourceResumeSha256Checksum(), request.toInput())));
    }

    @GetMapping("/{proposalId}")
    ResponseEntity<ProposalResponse> get(@PathVariable UUID proposalId) {
        return ok(ProposalResponse.from(service.getDraft(proposalId)));
    }

    @PutMapping("/{proposalId}")
    ResponseEntity<ProposalResponse> update(@PathVariable UUID proposalId, @RequestBody UpdateProposalRequest request) {
        return ok(ProposalResponse.from(service.updateDraft(proposalId, request.toInput(),
                request.expectedVersionValue())));
    }

    @DeleteMapping("/{proposalId}")
    ResponseEntity<Void> delete(@PathVariable UUID proposalId, @RequestBody VersionedRequest request) {
        service.deleteDraft(proposalId, request.expectedVersionValue());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/{proposalId}/review")
    ResponseEntity<ReviewResponse> review(@PathVariable UUID proposalId) {
        return ok(ReviewResponse.from(service.review(proposalId)));
    }

    @PostMapping("/{proposalId}/approve")
    ResponseEntity<DecisionResponse> approve(@PathVariable UUID proposalId, @RequestBody ApproveRequest request) {
        return ok(DecisionResponse.from(service.approve(proposalId, request.expectedVersionValue(),
                request.reviewedRevision(), Boolean.TRUE.equals(request.attestedExperienceAccurate()))));
    }

    @PostMapping("/{proposalId}/reject")
    ResponseEntity<DecisionResponse> reject(@PathVariable UUID proposalId, @RequestBody VersionedRequest request) {
        return ok(DecisionResponse.from(service.reject(proposalId, request.expectedVersionValue())));
    }

    @ExceptionHandler(ResumeTailoringNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "not_found");
    }

    @ExceptionHandler(ResumeTailoringConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ResumeTailoringConflictException conflict) {
        return problem(HttpStatus.CONFLICT, "Resume tailoring operation conflict", conflict.getMessage());
    }

    @ExceptionHandler(ResumeTailoringTooLargeException.class)
    ResponseEntity<Map<String, Object>> tooLarge() {
        return problem(HttpStatus.CONFLICT, "Resume tailoring collection is too large", "tailoring_too_large");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class, NullPointerException.class})
    ResponseEntity<Map<String, Object>> badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid resume tailoring request", "invalid_request");
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

    record CreateProposalRequest(
            UUID sourceResumeDocumentId,
            Long sourceResumeVersion,
            String sourceResumeSha256Checksum,
            ResumeTailoringTargetSection targetSection,
            String targetReference,
            String originalText,
            String proposedText,
            List<EvidenceRequest> evidence) {
        ResumeTailoringProposalInput toInput() {
            return new ResumeTailoringProposalInput(targetSection, targetReference, originalText, proposedText,
                    evidence == null ? List.of() : evidence.stream().map(EvidenceRequest::toInput).toList());
        }
        long sourceResumeVersionValue() {
            if (sourceResumeVersion == null) {
                throw new IllegalArgumentException("sourceResumeVersion is required");
            }
            return sourceResumeVersion;
        }
    }

    record UpdateProposalRequest(
            ResumeTailoringTargetSection targetSection,
            String targetReference,
            String originalText,
            String proposedText,
            List<EvidenceRequest> evidence,
            Long expectedVersion) {
        ResumeTailoringProposalInput toInput() {
            return new ResumeTailoringProposalInput(targetSection, targetReference, originalText, proposedText,
                    evidence == null ? List.of() : evidence.stream().map(EvidenceRequest::toInput).toList());
        }
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record EvidenceRequest(UUID careerFactId, String userNote) {
        ResumeTailoringEvidenceInput toInput() {
            return new ResumeTailoringEvidenceInput(careerFactId, userNote);
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

    record ApproveRequest(Long expectedVersion, String reviewedRevision, Boolean attestedExperienceAccurate) {
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record ProposalResponse(
            UUID id,
            SourceResumeResponse sourceResume,
            ResumeTailoringTargetSection targetSection,
            String targetReference,
            String originalText,
            String originalTextSource,
            String originalTextVerification,
            String proposedText,
            ResumeTailoringEvidenceState evidenceState,
            ResumeTailoringLifecycleStatus lifecycleStatus,
            List<EvidenceResponse> evidence,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        static ProposalResponse from(ResumeTailoringProposal proposal) {
            return new ProposalResponse(proposal.id(),
                    new SourceResumeResponse(proposal.sourceResumeDocumentId(), proposal.sourceResumeVersion(),
                            proposal.sourceResumeSha256Checksum()),
                    proposal.targetSection(), proposal.targetReference(), proposal.originalText(), "USER_SUPPLIED",
                    "NOT_CHECKED_AGAINST_DOCUMENT", proposal.proposedText(), proposal.evidenceState(),
                    proposal.lifecycleStatus(), proposal.evidence().stream().map(EvidenceResponse::from).toList(),
                    proposal.createdAt(), proposal.updatedAt(), proposal.version());
        }
    }

    record ReviewResponse(
            ProposalResponse proposal,
            ResumeTailoringApprovalEligibility eligibility,
            String reviewRevision,
            List<FactReferenceResponse> evidenceReferences,
            String originalTextNotice,
            String evidenceNotice,
            String approvalNotice) {
        static ReviewResponse from(ResumeTailoringReview review) {
            return new ReviewResponse(ProposalResponse.from(review.proposal()), review.eligibility(),
                    review.reviewToken(),
                    review.evidenceReferences().stream().map(FactReferenceResponse::from).toList(),
                    "Original text is user-supplied and was not checked against the source resume document.",
                    "Linked confirmed facts are user-selected support and do not prove every proposed claim.",
                    "Approval records user attestation only; it is not export readiness or permission to submit an application.");
        }
    }

    record EvidenceResponse(UUID id, UUID careerFactId, String userNote, Instant createdAt, Instant updatedAt,
            long version) {
        static EvidenceResponse from(ResumeTailoringProposalEvidence evidence) {
            return new EvidenceResponse(evidence.id(), evidence.careerFactId(), evidence.userNote(),
                    evidence.createdAt(), evidence.updatedAt(), evidence.version());
        }
    }

    record SourceResumeResponse(UUID documentId, long version, String sha256Checksum) {
    }

    record FactReferenceResponse(UUID careerFactId, long version) {
        static FactReferenceResponse from(ResumeTailoringFactReference reference) {
            return new FactReferenceResponse(reference.careerFactId(), reference.version());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DecisionResponse(UUID id, UUID proposalId, ResumeTailoringDecisionType decisionType, long proposalVersion,
            SourceResumeResponse sourceResume, Boolean attestedExperienceAccurate, Instant decidedAt,
            List<FactReferenceResponse> evidenceReferences) {
        static DecisionResponse from(ResumeTailoringDecision decision) {
            return new DecisionResponse(decision.id(), decision.proposalId(), decision.decisionType(),
                    decision.proposalVersion(),
                    new SourceResumeResponse(decision.sourceResumeDocumentId(), decision.sourceResumeVersion(),
                            decision.sourceResumeSha256Checksum()),
                    decision.attestedExperienceAccurate(), decision.decidedAt(),
                    decision.evidenceReferences().stream().map(FactReferenceResponse::from).toList());
        }
    }
}
