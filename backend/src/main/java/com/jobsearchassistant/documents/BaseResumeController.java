package com.jobsearchassistant.documents;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.jobsearchassistant.identity.api.UnauthenticatedActorException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents/base-resume")
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class BaseResumeController {
    private final BaseResumeService service;

    BaseResumeController(BaseResumeService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<BaseResumeResponse> current() {
        return ok(BaseResumeResponse.from(service.current()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<BaseResumeResponse> upload(@RequestParam("file") MultipartFile file) {
        BaseResumeDocument document = uploadDocument(file);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(BaseResumeResponse.from(document));
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<BaseResumeResponse> replace(
            @RequestParam("file") MultipartFile file,
            @RequestParam("expectedVersion") long expectedVersion) {
        return ok(BaseResumeResponse.from(replaceDocument(file, expectedVersion)));
    }

    @GetMapping("/download")
    ResponseEntity<InputStreamResource> download() {
        BaseResumeService.DownloadedBaseResume downloaded = service.download();
        BaseResumeDocument document = downloaded.document();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(document.originalFilename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(document.mediaType()))
                .contentLength(downloaded.download().byteSize())
                .body(new InputStreamResource(downloaded.download().stream()));
    }

    @ExceptionHandler(BaseResumeNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "not_found");
    }

    @ExceptionHandler(BaseResumeConflictException.class)
    ResponseEntity<Map<String, Object>> conflict(BaseResumeConflictException conflict) {
        return problem(HttpStatus.CONFLICT, "Document operation conflict", conflict.getMessage());
    }

    @ExceptionHandler({BaseResumeValidationException.class, MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> badRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Invalid document request", "invalid_file");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> tooLarge() {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Invalid document request", "invalid_file");
    }

    @ExceptionHandler(UnauthenticatedActorException.class)
    ResponseEntity<Map<String, Object>> unauthorized() {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication required", "authentication_required");
    }

    private ResponseEntity<BaseResumeResponse> ok(BaseResumeResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String title, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("title", title, "status", status.value(), "code", code));
    }

    private BaseResumeDocument uploadDocument(MultipartFile file) {
        try {
            return service.upload(file.getInputStream(), file.getOriginalFilename());
        } catch (IOException e) {
            throw new BaseResumeValidationException("invalid_file");
        }
    }

    private BaseResumeDocument replaceDocument(MultipartFile file, long expectedVersion) {
        try {
            return service.replace(file.getInputStream(), file.getOriginalFilename(), expectedVersion);
        } catch (IOException e) {
            throw new BaseResumeValidationException("invalid_file");
        }
    }

    record BaseResumeResponse(
            UUID id,
            String originalFilename,
            String mediaType,
            long byteSize,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        static BaseResumeResponse from(BaseResumeDocument document) {
            return new BaseResumeResponse(document.id(), document.originalFilename(), document.mediaType(),
                    document.byteSize(), document.createdAt(), document.updatedAt(), document.version());
        }
    }
}
