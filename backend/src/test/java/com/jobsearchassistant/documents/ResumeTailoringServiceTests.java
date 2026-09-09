package com.jobsearchassistant.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.jobsearchassistant.identity.api.ActorRole;
import com.jobsearchassistant.identity.api.AuthenticatedActor;
import org.junit.jupiter.api.Test;

class ResumeTailoringServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");
    private static final String DIGEST = "a".repeat(64);
    private static final String REPLACEMENT_DIGEST = "b".repeat(64);

    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID factId = UUID.randomUUID();
    private final FakeRepository repository = new FakeRepository();
    private final ResumeTailoringService service = new ResumeTailoringService(
            () -> new AuthenticatedActor(owner, ActorRole.ADMIN),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsDraftPinnedToExactResumeVersionAndDigestWithExplicitEvidence() {
        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 2, DIGEST));
        repository.confirmedFacts.add(new OwnerId(owner, factId));

        ResumeTailoringProposal created = service.createDraft(resumeId, 2, DIGEST, input(List.of(
                new ResumeTailoringEvidenceInput(factId, "User says this supports the wording."))));

        assertThat(created.ownerAccountId()).isEqualTo(owner);
        assertThat(created.sourceResumeDocumentId()).isEqualTo(resumeId);
        assertThat(created.sourceResumeVersion()).isEqualTo(2);
        assertThat(created.sourceResumeSha256Checksum()).isEqualTo(DIGEST);
        assertThat(created.evidenceState()).isEqualTo(ResumeTailoringEvidenceState.SUPPORTED_BY_CONFIRMED_FACTS);
        assertThat(created.evidence()).hasSize(1);
        assertThat(created.originalText()).isEqualTo("Old summary");
        assertThat(created.proposedText()).isEqualTo("New summary from a confirmed fact");
        assertThat(service.evaluateFutureApprovalEligibility(created.id()).eligible()).isTrue();
    }

    @Test
    void missingEvidenceIsExplicitAndNotApprovalEligible() {
        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 0, DIGEST));

        ResumeTailoringProposal created = service.createDraft(resumeId, 0, DIGEST, input(List.of()));

        assertThat(created.evidenceState()).isEqualTo(ResumeTailoringEvidenceState.MISSING_EVIDENCE);
        assertThat(service.evaluateFutureApprovalEligibility(created.id()).reasons())
                .containsExactly("missing_supporting_evidence");
    }

    @Test
    void rejectsMismatchedSourceResumeAndForeignEvidenceWithSafeNotFound() {
        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 1, DIGEST));
        repository.resumes.put(new OwnerId(other, resumeId), resume(other, resumeId, 1, DIGEST));
        repository.confirmedFacts.add(new OwnerId(other, factId));

        assertThatThrownBy(() -> service.createDraft(resumeId, 0, DIGEST, input(List.of())))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> service.createDraft(resumeId, 1, REPLACEMENT_DIGEST, input(List.of())))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
        assertThatThrownBy(() -> service.createDraft(resumeId, 1, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(factId, null)))))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
    }

    @Test
    void editsDraftWithOptimisticLockingWithoutRetargetingSource() {
        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 0, DIGEST));
        repository.confirmedFacts.add(new OwnerId(owner, factId));
        ResumeTailoringProposal created = service.createDraft(resumeId, 0, DIGEST, input(List.of()));

        ResumeTailoringProposal updated = service.updateDraft(created.id(),
                new ResumeTailoringProposalInput(ResumeTailoringTargetSection.EXPERIENCE, "Role bullet",
                        null, "Sharper owner-authored bullet", List.of(new ResumeTailoringEvidenceInput(factId, null))),
                0);

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.sourceResumeDocumentId()).isEqualTo(resumeId);
        assertThat(updated.sourceResumeVersion()).isZero();
        assertThat(updated.sourceResumeSha256Checksum()).isEqualTo(DIGEST);
        assertThat(updated.originalText()).isNull();
        assertThat(updated.evidence()).hasSize(1);
        assertThatThrownBy(() -> service.updateDraft(created.id(), input(List.of()), 0))
                .isInstanceOf(ResumeTailoringConflictException.class)
                .hasMessage("stale_version");
    }

    @Test
    void changedResumeAndUnconfirmedEvidenceInvalidateFutureApprovalEligibility() {
        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 0, DIGEST));
        repository.confirmedFacts.add(new OwnerId(owner, factId));
        ResumeTailoringProposal created = service.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(factId, null))));

        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 1, REPLACEMENT_DIGEST));
        repository.confirmedFacts.remove(new OwnerId(owner, factId));

        assertThat(service.evaluateFutureApprovalEligibility(created.id()).reasons())
                .containsExactly("source_resume_changed", "supporting_evidence_unavailable");
    }

    @Test
    void removesDraftWithExpectedVersion() {
        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 0, DIGEST));
        ResumeTailoringProposal created = service.createDraft(resumeId, 0, DIGEST, input(List.of()));

        assertThatThrownBy(() -> service.deleteDraft(created.id(), 1))
                .isInstanceOf(ResumeTailoringConflictException.class)
                .hasMessage("stale_version");
        service.deleteDraft(created.id(), 0);
        assertThatThrownBy(() -> service.getDraft(created.id()))
                .isInstanceOf(ResumeTailoringNotFoundException.class);
    }

    @Test
    void validatesBoundsAndDuplicateEvidence() {
        repository.resumes.put(new OwnerId(owner, resumeId), resume(owner, resumeId, 0, DIGEST));
        repository.confirmedFacts.add(new OwnerId(owner, factId));

        assertThatThrownBy(() -> service.createDraft(resumeId, 0, DIGEST,
                new ResumeTailoringProposalInput(null, "Summary", null, "text", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraft(resumeId, 0, DIGEST,
                new ResumeTailoringProposalInput(ResumeTailoringTargetSection.SUMMARY, "Summary", null,
                        "x".repeat(ResumeTailoringService.TEXT_MAX + 1), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraft(resumeId, 0, DIGEST,
                input(List.of(new ResumeTailoringEvidenceInput(factId, null),
                        new ResumeTailoringEvidenceInput(factId, "again")))))
                .isInstanceOf(ResumeTailoringConflictException.class)
                .hasMessage("duplicate_evidence_reference");
        assertThatThrownBy(() -> service.createDraft(resumeId, 0, DIGEST,
                input(java.util.stream.IntStream.range(0, ResumeTailoringService.MAX_EVIDENCE_REFERENCES + 1)
                        .mapToObj(i -> new ResumeTailoringEvidenceInput(UUID.randomUUID(), null))
                        .toList())))
                .isInstanceOf(ResumeTailoringTooLargeException.class);
    }

    private ResumeTailoringProposalInput input(List<ResumeTailoringEvidenceInput> evidence) {
        return new ResumeTailoringProposalInput(ResumeTailoringTargetSection.SUMMARY, "Professional summary",
                "Old summary", "New summary from a confirmed fact", evidence);
    }

    private BaseResumeDocument resume(UUID ownerId, UUID id, long version, String digest) {
        return new BaseResumeDocument(id, ownerId, "resume.pdf", "application/pdf", 10, digest,
                UUID.randomUUID().toString(), NOW, NOW, version);
    }

    private record OwnerId(UUID owner, UUID id) {
    }

    private static final class FakeRepository implements ResumeTailoringRepository {
        final Map<OwnerId, BaseResumeDocument> resumes = new HashMap<>();
        final Set<OwnerId> confirmedFacts = new HashSet<>();
        final Map<OwnerId, ResumeTailoringProposal> proposals = new HashMap<>();
        final Map<OwnerId, List<ResumeTailoringProposalEvidence>> evidence = new HashMap<>();

        public Optional<BaseResumeDocument> findBaseResume(UUID ownerAccountId, UUID resumeId) {
            return Optional.ofNullable(resumes.get(new OwnerId(ownerAccountId, resumeId)));
        }

        public boolean confirmedCareerFactExists(UUID ownerAccountId, UUID careerFactId) {
            return confirmedFacts.contains(new OwnerId(ownerAccountId, careerFactId));
        }

        public void insertProposal(ResumeTailoringProposal proposal) {
            proposals.put(new OwnerId(proposal.ownerAccountId(), proposal.id()), proposal);
        }

        public void insertEvidence(ResumeTailoringProposalEvidence item) {
            evidence.computeIfAbsent(new OwnerId(item.ownerAccountId(), item.proposalId()), key -> new ArrayList<>())
                    .add(item);
        }

        public Optional<ResumeTailoringProposal> findProposal(UUID ownerAccountId, UUID proposalId) {
            ResumeTailoringProposal proposal = proposals.get(new OwnerId(ownerAccountId, proposalId));
            if (proposal == null) {
                return Optional.empty();
            }
            return Optional.of(withEvidence(proposal, findEvidence(ownerAccountId, proposalId,
                    ResumeTailoringService.MAX_EVIDENCE_REFERENCES)));
        }

        public List<ResumeTailoringProposal> findProposals(UUID ownerAccountId, int limit) {
            return proposals.values().stream()
                    .filter(proposal -> proposal.ownerAccountId().equals(ownerAccountId))
                    .limit(limit)
                    .toList();
        }

        public int countProposals(UUID ownerAccountId) {
            return (int) proposals.values().stream()
                    .filter(proposal -> proposal.ownerAccountId().equals(ownerAccountId))
                    .count();
        }

        public List<ResumeTailoringProposalEvidence> findEvidence(UUID ownerAccountId, UUID proposalId, int limit) {
            return evidence.getOrDefault(new OwnerId(ownerAccountId, proposalId), List.of())
                    .stream()
                    .limit(limit)
                    .toList();
        }

        public int countEvidence(UUID ownerAccountId, UUID proposalId) {
            return evidence.getOrDefault(new OwnerId(ownerAccountId, proposalId), List.of()).size();
        }

        public boolean updateProposal(ResumeTailoringProposal proposal, long expectedVersion) {
            OwnerId key = new OwnerId(proposal.ownerAccountId(), proposal.id());
            ResumeTailoringProposal existing = proposals.get(key);
            if (existing == null || existing.version() != expectedVersion) {
                return false;
            }
            proposals.put(key, proposal);
            return true;
        }

        public void replaceEvidence(UUID ownerAccountId, UUID proposalId, List<ResumeTailoringProposalEvidence> items) {
            evidence.put(new OwnerId(ownerAccountId, proposalId), new ArrayList<>(items));
        }

        public boolean deleteProposal(UUID ownerAccountId, UUID proposalId, long expectedVersion) {
            OwnerId key = new OwnerId(ownerAccountId, proposalId);
            ResumeTailoringProposal existing = proposals.get(key);
            if (existing == null || existing.version() != expectedVersion) {
                return false;
            }
            proposals.remove(key);
            evidence.remove(key);
            return true;
        }

        private ResumeTailoringProposal withEvidence(
                ResumeTailoringProposal proposal,
                List<ResumeTailoringProposalEvidence> items) {
            return new ResumeTailoringProposal(proposal.id(), proposal.ownerAccountId(),
                    proposal.sourceResumeDocumentId(), proposal.sourceResumeVersion(),
                    proposal.sourceResumeSha256Checksum(), proposal.targetSection(), proposal.targetReference(),
                    proposal.originalText(), proposal.proposedText(), proposal.evidenceState(), items,
                    proposal.createdAt(), proposal.updatedAt(), proposal.version());
        }
    }
}
