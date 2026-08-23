package com.jobsearchassistant.applications;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jobsearchassistant.identity.api.UnauthenticatedActorException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
@RequestMapping("/api/applications")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ApplicationController {
    private final ApplicationService service;

    ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<List<ApplicationResponse>> list(
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) Integer limit) {
        return ok(service.listApplications(archived, status, limit).stream().map(ApplicationResponse::from).toList());
    }

    @PostMapping
    ResponseEntity<ApplicationResponse> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApplicationResponse.from(service.createApplication(request.toInput())));
    }

    @GetMapping("/{applicationId}")
    ResponseEntity<ApplicationResponse> get(@PathVariable UUID applicationId) {
        return ok(ApplicationResponse.from(service.getApplication(applicationId)));
    }

    @PutMapping("/{applicationId}")
    ResponseEntity<ApplicationResponse> update(@PathVariable UUID applicationId, @RequestBody UpdateRequest request) {
        return ok(ApplicationResponse.from(service.updateApplication(applicationId, request.toInput(),
                request.expectedVersionValue())));
    }

    @PostMapping("/{applicationId}/transitions")
    ResponseEntity<ApplicationResponse> transition(@PathVariable UUID applicationId,
            @RequestBody TransitionRequest request) {
        return ok(ApplicationResponse.from(service.transition(applicationId, request.toInput(),
                request.expectedVersionValue())));
    }

    @GetMapping("/{applicationId}/history")
    ResponseEntity<List<HistoryResponse>> history(@PathVariable UUID applicationId,
            @RequestParam(required = false) Integer limit) {
        return ok(service.listHistory(applicationId, limit).stream().map(HistoryResponse::from).toList());
    }

    @PostMapping("/{applicationId}/archive")
    ResponseEntity<ApplicationResponse> archive(@PathVariable UUID applicationId, @RequestBody VersionedRequest request) {
        return ok(ApplicationResponse.from(service.archive(applicationId, request.expectedVersionValue())));
    }

    @PostMapping("/{applicationId}/restore")
    ResponseEntity<ApplicationResponse> restore(@PathVariable UUID applicationId, @RequestBody VersionedRequest request) {
        return ok(ApplicationResponse.from(service.restore(applicationId, request.expectedVersionValue())));
    }

    @ExceptionHandler(ApplicationNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "not_found");
    }

    @ExceptionHandler(ApplicationConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ApplicationConflictException conflict) {
        return problem(HttpStatus.CONFLICT, "Application operation conflict", conflict.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class, NullPointerException.class})
    ResponseEntity<Map<String, Object>> badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid application request", "invalid_request");
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

    record CreateRequest(UUID jobId, String privateNotes, String nextActionText, LocalDate nextActionDueDate) {
        ApplicationInput toInput() {
            return new ApplicationInput(jobId, privateNotes, nextActionText, nextActionDueDate);
        }
    }

    record UpdateRequest(String privateNotes, String nextActionText, LocalDate nextActionDueDate, Long expectedVersion) {
        ApplicationUpdateInput toInput() {
            return new ApplicationUpdateInput(privateNotes, nextActionText, nextActionDueDate);
        }
        long expectedVersionValue() {
            if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion is required");
            return expectedVersion;
        }
    }

    record TransitionRequest(ApplicationStatus targetStatus, Long expectedVersion, Instant appliedAt, String note) {
        ApplicationTransitionInput toInput() {
            return new ApplicationTransitionInput(targetStatus, appliedAt, note);
        }
        long expectedVersionValue() {
            if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion is required");
            return expectedVersion;
        }
    }

    record VersionedRequest(Long expectedVersion) {
        long expectedVersionValue() {
            if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion is required");
            return expectedVersion;
        }
    }

    record ApplicationResponse(
            UUID id,
            UUID jobId,
            ApplicationStatus status,
            Instant appliedAt,
            String nextActionText,
            LocalDate nextActionDueDate,
            String privateNotes,
            Instant statusChangedAt,
            Instant createdAt,
            Instant updatedAt,
            long version,
            Instant archivedAt,
            boolean archived) {
        static ApplicationResponse from(JobApplication application) {
            NextAction nextAction = application.nextAction();
            return new ApplicationResponse(application.id(), application.jobId(), application.status(),
                    application.appliedAt(), nextAction == null ? null : nextAction.text(),
                    nextAction == null ? null : nextAction.dueDate(), application.privateNotes(),
                    application.statusChangedAt(), application.createdAt(), application.updatedAt(),
                    application.version(), application.archivedAt(), application.archived());
        }
    }

    record HistoryResponse(UUID id, ApplicationStatus previousStatus, ApplicationStatus newStatus,
            Instant effectiveAt, String note, Instant recordedAt) {
        static HistoryResponse from(ApplicationStatusHistory history) {
            return new HistoryResponse(history.id(), history.previousStatus(), history.newStatus(),
                    history.effectiveAt(), history.note(), history.recordedAt());
        }
    }
}
