package com.jobsearchassistant.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobDomainTests {
    private final Instant now = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void capturedJobNormalizesRequiredMetadataAndArchivesIndependently() {
        CapturedJob job = new CapturedJob(UUID.randomUUID(), UUID.randomUUID(), "  Acme   Corp ",
                " Senior   Engineer ", " Remote ", PostingUrl.optional("HTTPS://Example.COM/jobs/1#apply"),
                JobSourceType.URL_REFERENCE, EmploymentType.FULL_TIME, " ext-1 ", null, now, now, 0, now);

        assertThat(job.companyName()).isEqualTo("Acme Corp");
        assertThat(job.jobTitle()).isEqualTo("Senior Engineer");
        assertThat(job.postingUrl().value()).isEqualTo("https://example.com/jobs/1");
        assertThat(job.archived()).isTrue();
    }

    @Test
    void capturedJobRejectsInvalidTextTimestampsAndVersions() {
        assertThatThrownBy(() -> new CapturedJob(UUID.randomUUID(), UUID.randomUUID(), " ", "Engineer",
                null, null, JobSourceType.MANUAL, null, null, null, now, now, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("companyName");
        assertThatThrownBy(() -> new CapturedJob(UUID.randomUUID(), UUID.randomUUID(), "Acme", "Engineer",
                null, null, JobSourceType.MANUAL, null, null, null, now, now.minusSeconds(1), 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataUpdatedAt");
        assertThatThrownBy(() -> new CapturedJob(UUID.randomUUID(), UUID.randomUUID(), "Acme", "Engineer",
                null, null, JobSourceType.MANUAL, null, null, null, now, now, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void postingUrlAcceptsOnlySafeHttpReferences() {
        assertThat(PostingUrl.optional(null)).isNull();
        assertThatThrownBy(() -> new PostingUrl("ftp://example.com/job")).hasMessageContaining("http");
        assertThatThrownBy(() -> new PostingUrl("https://user:pass@example.com/job")).hasMessageContaining("credentials");
        assertThatThrownBy(() -> new PostingUrl("https:///missing-host")).hasMessageContaining("host");
    }

    @Test
    void snapshotCanonicalizesLineEndingsAndHashesStoredContent() {
        JobDescriptionSnapshot snapshot = new JobDescriptionSnapshot(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, JobSourceType.PASTED_DESCRIPTION, "  First\r\n\r\nSecond\rThird  ", now);

        assertThat(snapshot.descriptionText()).isEqualTo("First\n\nSecond\nThird");
        assertThat(snapshot.sha256Digest()).isEqualTo(JobDescriptionSnapshot.digest(snapshot.descriptionText()));
        assertThat(snapshot.sha256Digest()).matches("[0-9a-f]{64}");
    }

    @Test
    void snapshotRejectsBlankOversizedOrNonpositiveSequence() {
        assertThatThrownBy(() -> new JobDescriptionSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                0, JobSourceType.MANUAL, "text", now)).hasMessageContaining("sequence");
        assertThatThrownBy(() -> new JobDescriptionSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, JobSourceType.MANUAL, " \r\n ", now)).hasMessageContaining("descriptionText");
    }
}
