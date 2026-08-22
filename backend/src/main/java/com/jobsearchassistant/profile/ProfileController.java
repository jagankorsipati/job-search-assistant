package com.jobsearchassistant.profile;

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
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestController
@RequestMapping("/api/profile")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ProfileController {
    private final ProfileService service;

    ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<ProfileResponse> getProfile() {
        return ok(ProfileResponse.from(service.getProfile()));
    }

    @PostMapping
    ResponseEntity<ProfileResponse> createProfile(@RequestBody ProfileRequest request) {
        CandidateProfile profile = service.createProfile(request.toInput());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ProfileResponse.from(profile));
    }

    @PutMapping
    ResponseEntity<ProfileResponse> updateProfile(@RequestBody ProfileUpdateRequest request) {
        CandidateProfile profile = service.updateProfile(request.toInput(), request.expectedVersionValue());
        return ok(ProfileResponse.from(profile));
    }

    @GetMapping("/career-facts")
    ResponseEntity<List<CareerFactResponse>> listFacts(
            @RequestParam(required = false) CareerFactCategory category,
            @RequestParam(required = false) CareerFactStatus status,
            @RequestParam(required = false) Integer limit) {
        return ok(service.listFacts(category, status, limit).stream().map(CareerFactResponse::from).toList());
    }

    @PostMapping("/career-facts")
    ResponseEntity<CareerFactResponse> createFact(@RequestBody CareerFactRequest request) {
        CareerFact fact = service.createFact(request.toInput());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(CareerFactResponse.from(fact));
    }

    @GetMapping("/career-facts/{factId}")
    ResponseEntity<CareerFactResponse> getFact(@PathVariable UUID factId) {
        return ok(CareerFactResponse.from(service.getFact(factId)));
    }

    @PutMapping("/career-facts/{factId}")
    ResponseEntity<CareerFactResponse> updateFact(@PathVariable UUID factId, @RequestBody CareerFactUpdateRequest request) {
        return ok(CareerFactResponse.from(service.updateFact(factId, request.toInput(), request.expectedVersionValue())));
    }

    @PostMapping("/career-facts/{factId}/confirm")
    ResponseEntity<CareerFactResponse> confirm(@PathVariable UUID factId, @RequestBody ConfirmFactRequest request) {
        return ok(CareerFactResponse.from(service.confirmFact(factId, request.expectedVersionValue(),
                Boolean.TRUE.equals(request.confirmedAccurate()))));
    }

    @PostMapping("/career-facts/{factId}/archive")
    ResponseEntity<CareerFactResponse> archive(@PathVariable UUID factId, @RequestBody VersionedRequest request) {
        return ok(CareerFactResponse.from(service.archiveFact(factId, request.expectedVersionValue())));
    }

    @PostMapping("/career-facts/{factId}/restore")
    ResponseEntity<CareerFactResponse> restore(@PathVariable UUID factId, @RequestBody VersionedRequest request) {
        return ok(CareerFactResponse.from(service.restoreFact(factId, request.expectedVersionValue())));
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "not_found");
    }

    @ExceptionHandler(ProfileConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(ProfileConflictException conflict) {
        return problem(HttpStatus.CONFLICT, "Profile operation conflict", conflict.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class, NullPointerException.class})
    ResponseEntity<Map<String, Object>> badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid profile request", "invalid_request");
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

    record ProfileRequest(
            String professionalDisplayName,
            String professionalHeadline,
            String careerSummary,
            String locationPreference,
            String targetRoles,
            String workAuthorizationStatement,
            String workLocationPreferences) {
        ProfileInput toInput() {
            return new ProfileInput(professionalDisplayName, professionalHeadline, careerSummary, locationPreference,
                    targetRoles, workAuthorizationStatement, workLocationPreferences);
        }
    }

    record ProfileUpdateRequest(
            String professionalDisplayName,
            String professionalHeadline,
            String careerSummary,
            String locationPreference,
            String targetRoles,
            String workAuthorizationStatement,
            String workLocationPreferences,
            Long expectedVersion) {
        ProfileInput toInput() {
            return new ProfileInput(professionalDisplayName, professionalHeadline, careerSummary, locationPreference,
                    targetRoles, workAuthorizationStatement, workLocationPreferences);
        }
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record ProfileResponse(
            UUID id,
            String professionalDisplayName,
            String professionalHeadline,
            String careerSummary,
            String locationPreference,
            String targetRoles,
            String workAuthorizationStatement,
            String workLocationPreferences,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        static ProfileResponse from(CandidateProfile profile) {
            return new ProfileResponse(profile.id(), profile.professionalDisplayName(), profile.professionalHeadline(),
                    profile.careerSummary(), profile.locationPreference(), profile.targetRoles(),
                    profile.workAuthorizationStatement(), profile.workLocationPreferences(), profile.createdAt(),
                    profile.updatedAt(), profile.version());
        }
    }

    record CareerFactRequest(
            CareerFactCategory category,
            String factualContent,
            String organization,
            String title,
            String location,
            LocalDate startedOn,
            LocalDate endedOn,
            Boolean ongoing) {
        CareerFactInput toInput() {
            return new CareerFactInput(category, factualContent, organization, title, location, startedOn, endedOn, ongoing);
        }
    }

    record CareerFactUpdateRequest(
            CareerFactCategory category,
            String factualContent,
            String organization,
            String title,
            String location,
            LocalDate startedOn,
            LocalDate endedOn,
            Boolean ongoing,
            Long expectedVersion) {
        CareerFactInput toInput() {
            return new CareerFactInput(category, factualContent, organization, title, location, startedOn, endedOn, ongoing);
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

    record ConfirmFactRequest(Long expectedVersion, Boolean confirmedAccurate) {
        long expectedVersionValue() {
            if (expectedVersion == null) {
                throw new IllegalArgumentException("expectedVersion is required");
            }
            return expectedVersion;
        }
    }

    record CareerFactResponse(
            UUID id,
            CareerFactCategory category,
            CareerFactStatus status,
            String factualContent,
            String organization,
            String title,
            String location,
            LocalDate startedOn,
            LocalDate endedOn,
            boolean ongoing,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        static CareerFactResponse from(CareerFact fact) {
            return new CareerFactResponse(fact.id(), fact.category(), fact.status(), fact.factualContent(),
                    fact.organization(), fact.title(), fact.location(), fact.startedOn(), fact.endedOn(),
                    fact.ongoing(), fact.createdAt(), fact.updatedAt(), fact.version());
        }
    }
}
