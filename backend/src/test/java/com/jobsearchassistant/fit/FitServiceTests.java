package com.jobsearchassistant.fit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.AuthenticatedActor;
import org.junit.jupiter.api.Test;

class FitServiceTests {
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final FakeFitRepository repository = new FakeFitRepository();
    private final FitService service = new FitService(
            () -> new AuthenticatedActor(owner, ActorRole.MEMBER),
            repository,
            Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void requirementCreationAndUpdatesAreOwnerScopedAndVersioned() {
        repository.snapshots.add(new SnapshotKey(owner, jobId, snapshotId));

        JobRequirement created = service.createRequirement(jobId, snapshotId,
                new RequirementInput(RequirementCategory.SKILL, RequirementImportance.REQUIRED,
                        "Java", "Must know Java", RequirementStatus.DRAFT));

        assertThat(created.ownerAccountId()).isEqualTo(owner);
        assertThat(created.jobSnapshotId()).isEqualTo(snapshotId);
        assertThat(created.status()).isEqualTo(RequirementStatus.DRAFT);

        JobRequirement updated = service.updateRequirement(created.id(),
                new RequirementInput(RequirementCategory.EXPERIENCE, RequirementImportance.PREFERRED,
                        "Five years", null, RequirementStatus.CONFIRMED), 0);

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.status()).isEqualTo(RequirementStatus.CONFIRMED);
        assertThatThrownBy(() -> service.updateRequirement(created.id(),
                new RequirementInput(RequirementCategory.OTHER, RequirementImportance.UNSPECIFIED,
                        "stale", null, RequirementStatus.REJECTED), 0))
                .isInstanceOf(FitConflictException.class)
                .hasMessage("stale_version");
        assertThat(repository.requirements.getFirst().requirementText()).isEqualTo("Five years");
    }

    @Test
    void snapshotMismatchAndOversizedRequirementAreRejected() {
        repository.snapshots.add(new SnapshotKey(owner, jobId, snapshotId));

        assertThatThrownBy(() -> service.createRequirement(jobId, UUID.randomUUID(),
                new RequirementInput(RequirementCategory.SKILL, RequirementImportance.REQUIRED,
                        "Java", null, RequirementStatus.DRAFT)))
                .isInstanceOf(FitNotFoundException.class);
        assertThatThrownBy(() -> service.createRequirement(jobId, snapshotId,
                new RequirementInput(RequirementCategory.SKILL, RequirementImportance.REQUIRED,
                        "x".repeat(FitService.REQUIREMENT_TEXT_MAX + 1), null, RequirementStatus.DRAFT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceLinksRequireExistingOwnerVisibleConfirmedEvidence() {
        repository.snapshots.add(new SnapshotKey(owner, jobId, snapshotId));
        JobRequirement requirement = service.createRequirement(jobId, snapshotId,
                new RequirementInput(RequirementCategory.SKILL, RequirementImportance.REQUIRED,
                        "Java", null, RequirementStatus.CONFIRMED));
        UUID confirmedFact = UUID.randomUUID();
        UUID draftFact = UUID.randomUUID();
        repository.confirmedFacts.add(new OwnerId(owner, confirmedFact));
        repository.draftFacts.add(new OwnerId(owner, draftFact));

        CandidateEvidenceLink partial = service.createEvidenceLink(requirement.id(),
                new EvidenceLinkInput(EvidenceType.CAREER_FACT, confirmedFact,
                        EvidenceRelationship.PARTIALLY_SUPPORTS, "close"));

        assertThat(partial.relationship()).isEqualTo(EvidenceRelationship.PARTIALLY_SUPPORTS);
        assertThatThrownBy(() -> service.createEvidenceLink(requirement.id(),
                new EvidenceLinkInput(EvidenceType.CAREER_FACT, draftFact,
                        EvidenceRelationship.SUPPORTS, null)))
                .isInstanceOf(FitNotFoundException.class);
        assertThatThrownBy(() -> service.createEvidenceLink(requirement.id(),
                new EvidenceLinkInput(EvidenceType.CAREER_FACT, UUID.randomUUID(),
                        EvidenceRelationship.SUPPORTS, null)))
                .isInstanceOf(FitNotFoundException.class);
        assertThatThrownBy(() -> service.createEvidenceLink(requirement.id(),
                new EvidenceLinkInput(EvidenceType.CAREER_FACT, confirmedFact,
                        EvidenceRelationship.CONTRADICTS, null)))
                .isInstanceOf(FitConflictException.class)
                .hasMessage("duplicate_evidence_link");
    }

    @Test
    void profileAndResumeEvidenceAreExplicitReferencesOnly() {
        repository.snapshots.add(new SnapshotKey(owner, jobId, snapshotId));
        repository.profileOwners.add(owner);
        UUID resumeId = UUID.randomUUID();
        repository.resumes.add(new OwnerId(owner, resumeId));
        JobRequirement requirement = service.createRequirement(jobId, snapshotId,
                new RequirementInput(RequirementCategory.LOCATION, RequirementImportance.UNSPECIFIED,
                        "Remote", null, RequirementStatus.CONFIRMED));

        CandidateEvidenceLink profile = service.createEvidenceLink(requirement.id(),
                new EvidenceLinkInput(EvidenceType.PROFILE_FIELD, ProfileEvidenceField.WORK_LOCATION_PREFERENCES_ID,
                        EvidenceRelationship.SUPPORTS, null));
        CandidateEvidenceLink resume = service.createEvidenceLink(requirement.id(),
                new EvidenceLinkInput(EvidenceType.RESUME_VERSION, resumeId,
                        EvidenceRelationship.NOT_DEMONSTRATED, null));

        assertThat(profile.evidenceType()).isEqualTo(EvidenceType.PROFILE_FIELD);
        assertThat(resume.relationship()).isEqualTo(EvidenceRelationship.NOT_DEMONSTRATED);
        assertThatThrownBy(() -> service.createEvidenceLink(requirement.id(),
                new EvidenceLinkInput(EvidenceType.PROFILE_FIELD, UUID.randomUUID(),
                        EvidenceRelationship.SUPPORTS, null)))
                .isInstanceOf(FitNotFoundException.class);
    }

    private record SnapshotKey(UUID owner, UUID jobId, UUID snapshotId) {
    }

    private record OwnerId(UUID owner, UUID id) {
    }

    private static final class FakeFitRepository implements FitRepository {
        final Set<SnapshotKey> snapshots = new HashSet<>();
        final List<JobRequirement> requirements = new ArrayList<>();
        final List<CandidateEvidenceLink> links = new ArrayList<>();
        final Set<OwnerId> confirmedFacts = new HashSet<>();
        final Set<OwnerId> draftFacts = new HashSet<>();
        final Set<UUID> profileOwners = new HashSet<>();
        final Set<OwnerId> resumes = new HashSet<>();

        public boolean ownedSnapshotExists(UUID ownerAccountId, UUID jobId, UUID snapshotId) {
            return snapshots.contains(new SnapshotKey(ownerAccountId, jobId, snapshotId));
        }

        public List<JobRequirement> findRequirements(UUID ownerAccountId, UUID jobId, UUID snapshotId, int limit) {
            return requirements.stream()
                    .filter(r -> r.ownerAccountId().equals(ownerAccountId) && r.jobId().equals(jobId)
                            && r.jobSnapshotId().equals(snapshotId))
                    .limit(limit).toList();
        }

        public Optional<JobRequirement> findRequirement(UUID requirementId, UUID ownerAccountId) {
            return requirements.stream()
                    .filter(r -> r.id().equals(requirementId) && r.ownerAccountId().equals(ownerAccountId))
                    .findFirst();
        }

        public void insertRequirement(JobRequirement requirement) {
            requirements.add(requirement);
        }

        public boolean updateRequirement(JobRequirement requirement, long expectedVersion) {
            for (int i = 0; i < requirements.size(); i++) {
                JobRequirement existing = requirements.get(i);
                if (existing.id().equals(requirement.id()) && existing.ownerAccountId().equals(requirement.ownerAccountId())
                        && existing.version() == expectedVersion) {
                    requirements.set(i, requirement);
                    return true;
                }
            }
            return false;
        }

        public boolean deleteRequirement(UUID requirementId, UUID ownerAccountId, long expectedVersion) {
            return requirements.removeIf(r -> r.id().equals(requirementId)
                    && r.ownerAccountId().equals(ownerAccountId) && r.version() == expectedVersion);
        }

        public List<CandidateEvidenceLink> findEvidenceLinks(UUID ownerAccountId, UUID requirementId, int limit) {
            return links.stream().filter(l -> l.ownerAccountId().equals(ownerAccountId)
                    && l.jobRequirementId().equals(requirementId)).limit(limit).toList();
        }

        public Optional<CandidateEvidenceLink> findEvidenceLink(UUID linkId, UUID ownerAccountId) {
            return links.stream().filter(l -> l.id().equals(linkId) && l.ownerAccountId().equals(ownerAccountId)).findFirst();
        }

        public void insertEvidenceLink(CandidateEvidenceLink link) {
            if (links.stream().anyMatch(existing -> existing.ownerAccountId().equals(link.ownerAccountId())
                    && existing.jobRequirementId().equals(link.jobRequirementId())
                    && existing.evidenceType() == link.evidenceType()
                    && existing.evidenceId().equals(link.evidenceId()))) {
                throw new org.springframework.dao.DuplicateKeyException("duplicate");
            }
            links.add(link);
        }

        public boolean updateEvidenceLink(CandidateEvidenceLink link, long expectedVersion) {
            for (int i = 0; i < links.size(); i++) {
                CandidateEvidenceLink existing = links.get(i);
                if (existing.id().equals(link.id()) && existing.ownerAccountId().equals(link.ownerAccountId())
                        && existing.version() == expectedVersion) {
                    links.set(i, link);
                    return true;
                }
            }
            return false;
        }

        public boolean deleteEvidenceLink(UUID linkId, UUID ownerAccountId, long expectedVersion) {
            return links.removeIf(l -> l.id().equals(linkId) && l.ownerAccountId().equals(ownerAccountId)
                    && l.version() == expectedVersion);
        }

        public boolean confirmedCareerFactExists(UUID ownerAccountId, UUID factId) {
            return confirmedFacts.contains(new OwnerId(ownerAccountId, factId));
        }

        public boolean profileExists(UUID ownerAccountId) {
            return profileOwners.contains(ownerAccountId);
        }

        public boolean baseResumeExists(UUID ownerAccountId, UUID resumeId) {
            return resumes.contains(new OwnerId(ownerAccountId, resumeId));
        }
    }
}
