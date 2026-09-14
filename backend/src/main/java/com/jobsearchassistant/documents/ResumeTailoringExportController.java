package com.jobsearchassistant.documents;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import com.jobsearchassistant.identity.api.UnauthenticatedActorException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/documents/resume-tailoring-proposals/{id}")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ResumeTailoringExportController {
    private final ResumeTailoringExportService service;
    ResumeTailoringExportController(ResumeTailoringExportService service) { this.service = service; }

    @GetMapping("/resolved-review")
    ResponseEntity<ResolvedReviewResponse> review(@PathVariable UUID id, @RequestParam long expectedVersion) {
        var review = service.review(id, expectedVersion);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ResolvedReviewResponse(
                ResumeTailoringController.ReviewResponse.from(review.review()), review.sourceText(),
                ResumeTailoringExportService.POLICY, "word/document.xml", review.anchor().bodyChildIndex(),
                review.anchor().paragraphSha256(), review.resolvedRevision(), review.exportApproved()));
    }

    @PostMapping("/approve-resolved")
    ResponseEntity<ResolvedApprovalResponse> approve(@PathVariable UUID id, @RequestBody ApprovalRequest request) {
        var decision = service.approve(id, request.version(), request.resolvedRevision(), Boolean.TRUE.equals(request.attestedTargetAndExperience()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ResolvedApprovalResponse(
                decision.id(), decision.proposalId(), decision.proposalVersion(), decision.decisionType(), decision.decidedAt()));
    }

    @PostMapping("/export")
    ResponseEntity<ByteArrayResource> export(@PathVariable UUID id, @RequestBody ExportRequest request) {
        byte[] bytes = service.export(id, request.version(), request.resolvedRevision());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("tailored-resume.docx").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(BaseResumeValidator.DOCX)).contentLength(bytes.length).body(new ByteArrayResource(bytes));
    }

    record ResolvedReviewResponse(ResumeTailoringController.ReviewResponse review, String sourceText,
            String targetPolicy, String documentPart, int bodyChildIndex, String paragraphSha256,
            String resolvedRevision, boolean exportApproved) {
        @Override public String toString() { return "ResolvedReviewResponse[redacted]"; }
    }
    record ResolvedApprovalResponse(UUID id, UUID proposalId, long proposalVersion,
            ResumeTailoringDecisionType decisionType, Instant decidedAt) {
        @Override public String toString() { return "ResolvedApprovalResponse[redacted]"; }
    }
    record ApprovalRequest(Long expectedVersion, String resolvedRevision, Boolean attestedTargetAndExperience) {
        long version() { if (expectedVersion == null) throw new IllegalArgumentException("invalid_version"); return expectedVersion; }
        @Override public String toString() { return "ApprovalRequest[redacted]"; }
    }
    record ExportRequest(Long expectedVersion, String resolvedRevision) {
        long version() { if (expectedVersion == null) throw new IllegalArgumentException("invalid_version"); return expectedVersion; }
        @Override public String toString() { return "ExportRequest[redacted]"; }
    }

    @ExceptionHandler(ResumeTailoringNotFoundException.class)
    ResponseEntity<Map<String, Object>> missing() { return problem(HttpStatus.NOT_FOUND, "not_found"); }
    @ExceptionHandler(ResumeTailoringConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ResumeTailoringConflictException failure) { return problem(HttpStatus.CONFLICT, failure.getMessage()); }
    @ExceptionHandler(DocxReplacementSpike.Refusal.class)
    ResponseEntity<Map<String, Object>> unsupported() { return problem(HttpStatus.CONFLICT, "export_unsupported"); }
    @ExceptionHandler(ResumeTailoringTooLargeException.class)
    ResponseEntity<Map<String, Object>> large() { return problem(HttpStatus.CONFLICT, "tailoring_too_large"); }
    @ExceptionHandler(UnauthenticatedActorException.class)
    ResponseEntity<Map<String, Object>> unauthorized() { return problem(HttpStatus.UNAUTHORIZED, "authentication_required"); }
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<Map<String, Object>> invalid() { return problem(HttpStatus.BAD_REQUEST, "invalid_request"); }
    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("title", "Document operation unavailable", "status", status.value(), "code", code));
    }
}
