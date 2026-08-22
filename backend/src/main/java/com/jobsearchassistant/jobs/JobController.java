package com.jobsearchassistant.jobs;

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
@RequestMapping("/api/jobs")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class JobController {
    private final JobService service;

    JobController(JobService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<List<JobResponse>> list(
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) Integer limit) {
        return ok(service.listJobs(archived, limit).stream().map(JobResponse::from).toList());
    }

    @PostMapping
    ResponseEntity<CaptureResponse> capture(@RequestBody CaptureRequest request) {
        JobService.CapturedJobWithSnapshot created = service.capture(request.toInput(), request.descriptionText());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(CaptureResponse.from(created));
    }

    @GetMapping("/{jobId}")
    ResponseEntity<JobResponse> get(@PathVariable UUID jobId) {
        return ok(JobResponse.from(service.getJob(jobId)));
    }

    @PutMapping("/{jobId}")
    ResponseEntity<JobResponse> update(@PathVariable UUID jobId, @RequestBody JobUpdateRequest request) {
        return ok(JobResponse.from(service.updateJob(jobId, request.toInput(), request.expectedVersionValue())));
    }

    @PostMapping("/{jobId}/archive")
    ResponseEntity<JobResponse> archive(@PathVariable UUID jobId, @RequestBody VersionedRequest request) {
        return ok(JobResponse.from(service.archive(jobId, request.expectedVersionValue())));
    }

    @PostMapping("/{jobId}/restore")
    ResponseEntity<JobResponse> restore(@PathVariable UUID jobId, @RequestBody VersionedRequest request) {
        return ok(JobResponse.from(service.restore(jobId, request.expectedVersionValue())));
    }

    @GetMapping("/{jobId}/snapshots")
    ResponseEntity<List<SnapshotResponse>> snapshots(
            @PathVariable UUID jobId,
            @RequestParam(required = false) Integer limit) {
        return ok(service.listSnapshots(jobId, limit).stream().map(SnapshotResponse::from).toList());
    }

    @GetMapping("/{jobId}/snapshots/{snapshotId}")
    ResponseEntity<SnapshotResponse> snapshot(@PathVariable UUID jobId, @PathVariable UUID snapshotId) {
        return ok(SnapshotResponse.from(service.getSnapshot(jobId, snapshotId)));
    }

    @PostMapping("/{jobId}/snapshots")
    ResponseEntity<SnapshotResponse> appendSnapshot(
            @PathVariable UUID jobId,
            @RequestBody SnapshotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(SnapshotResponse.from(service.appendSnapshot(jobId, request.sourceType(), request.descriptionText())));
    }

    @ExceptionHandler(JobNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "not_found");
    }

    @ExceptionHandler(JobConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(JobConflictException conflict) {
        return problem(HttpStatus.CONFLICT, "Job operation conflict", conflict.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class, NullPointerException.class})
    ResponseEntity<Map<String, Object>> badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid job request", "invalid_request");
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

    record CaptureRequest(
            String companyName,
            String jobTitle,
            String workLocation,
            String postingUrl,
            JobSourceType sourceType,
            EmploymentType employmentType,
            String externalPostingId,
            LocalDate datePosted,
            String descriptionText) {
        JobInput toInput() {
            return new JobInput(companyName, jobTitle, workLocation, postingUrl, sourceType,
                    employmentType, externalPostingId, datePosted);
        }
    }

    record JobUpdateRequest(
            String companyName,
            String jobTitle,
            String workLocation,
            String postingUrl,
            JobSourceType sourceType,
            EmploymentType employmentType,
            String externalPostingId,
            LocalDate datePosted,
            Long expectedVersion) {
        JobInput toInput() {
            return new JobInput(companyName, jobTitle, workLocation, postingUrl, sourceType,
                    employmentType, externalPostingId, datePosted);
        }
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record SnapshotRequest(JobSourceType sourceType, String descriptionText) {
    }

    record VersionedRequest(Long expectedVersion) {
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record CaptureResponse(JobResponse job, SnapshotResponse initialSnapshot) {
        static CaptureResponse from(JobService.CapturedJobWithSnapshot created) {
            return new CaptureResponse(JobResponse.from(created.job()),
                    created.initialSnapshot() == null ? null : SnapshotResponse.from(created.initialSnapshot()));
        }
    }

    record JobResponse(
            UUID id,
            String companyName,
            String jobTitle,
            String workLocation,
            String postingUrl,
            JobSourceType sourceType,
            EmploymentType employmentType,
            String externalPostingId,
            LocalDate datePosted,
            Instant capturedAt,
            Instant metadataUpdatedAt,
            long version,
            Instant archivedAt,
            boolean archived) {
        static JobResponse from(CapturedJob job) {
            return new JobResponse(job.id(), job.companyName(), job.jobTitle(), job.workLocation(),
                    job.postingUrl() == null ? null : job.postingUrl().value(), job.sourceType(),
                    job.employmentType(), job.externalPostingId(), job.datePosted(), job.capturedAt(),
                    job.metadataUpdatedAt(), job.version(), job.archivedAt(), job.archived());
        }
    }

    record SnapshotResponse(
            UUID id,
            UUID jobId,
            int sequence,
            JobSourceType sourceType,
            String descriptionText,
            String sha256Digest,
            Instant capturedAt) {
        static SnapshotResponse from(JobDescriptionSnapshot snapshot) {
            return new SnapshotResponse(snapshot.id(), snapshot.jobId(), snapshot.sequence(), snapshot.sourceType(),
                    snapshot.descriptionText(), snapshot.sha256Digest(), snapshot.capturedAt());
        }
    }
}
