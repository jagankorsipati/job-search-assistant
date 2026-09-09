package com.jobsearchassistant.documents;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ResumeTailoringService {
    static final int DEFAULT_PROPOSAL_LIMIT = 100;
    static final int MAX_PROPOSAL_LIMIT = 100;
    static final int MAX_EVIDENCE_REFERENCES = 25;
    static final int TARGET_REFERENCE_MAX = 200;
    static final int TEXT_MAX = 4_000;
    static final int EVIDENCE_NOTE_MAX = 1_000;

    private final CurrentActorProvider actors;
    private final ResumeTailoringRepository repository;
    private final Clock clock;

    @Autowired
    ResumeTailoringService(CurrentActorProvider actors, ResumeTailoringRepository repository) {
        this(actors, repository, Clock.systemUTC());
    }

    ResumeTailoringService(CurrentActorProvider actors, ResumeTailoringRepository repository, Clock clock) {
        this.actors = actors;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<ResumeTailoringProposal> listDrafts(Integer requestedLimit) {
        UUID owner = owner();
        if (repository.countProposals(owner) > boundedLimit(requestedLimit)) {
            throw new ResumeTailoringTooLargeException();
        }
        return repository.findProposals(owner, boundedLimit(requestedLimit));
    }

    @Transactional(readOnly = true)
    ResumeTailoringProposal getDraft(UUID proposalId) {
        UUID owner = owner();
        ResumeTailoringProposal proposal = repository.findProposal(owner, proposalId)
                .orElseThrow(ResumeTailoringNotFoundException::new);
        ensureEvidenceBound(owner, proposal.id());
        return proposal;
    }

    @Transactional
    ResumeTailoringProposal createDraft(UUID sourceResumeDocumentId, long sourceResumeVersion,
            String sourceResumeSha256Checksum, ResumeTailoringProposalInput input) {
        requireVersion(sourceResumeVersion);
        UUID owner = owner();
        BaseResumeDocument source = requireExactSourceResume(owner, sourceResumeDocumentId,
                sourceResumeVersion, sourceResumeSha256Checksum);
        ResumeTailoringProposalInput clean = validateInput(input);
        Instant now = clock.instant();
        UUID proposalId = UUID.randomUUID();
        List<ResumeTailoringProposalEvidence> evidence = evidence(owner, proposalId, clean.evidence(), now);
        ResumeTailoringProposal proposal = new ResumeTailoringProposal(proposalId, owner, source.id(),
                source.version(), source.sha256Checksum(), clean.targetSection(), clean.targetReference(),
                clean.originalText(), clean.proposedText(), evidenceState(evidence), evidence, now, now, 0);
        try {
            repository.insertProposal(proposal);
            evidence.forEach(repository::insertEvidence);
        } catch (DuplicateKeyException duplicate) {
            throw new ResumeTailoringConflictException("duplicate_evidence_reference");
        }
        return repository.findProposal(owner, proposal.id()).orElse(proposal);
    }

    @Transactional
    ResumeTailoringProposal updateDraft(UUID proposalId, ResumeTailoringProposalInput input, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        ResumeTailoringProposal existing = repository.findProposal(owner, proposalId)
                .orElseThrow(ResumeTailoringNotFoundException::new);
        ResumeTailoringProposalInput clean = validateInput(input);
        Instant now = clock.instant();
        List<ResumeTailoringProposalEvidence> evidence = evidence(owner, proposalId, clean.evidence(), now);
        ResumeTailoringProposal updated = new ResumeTailoringProposal(existing.id(), existing.ownerAccountId(),
                existing.sourceResumeDocumentId(), existing.sourceResumeVersion(), existing.sourceResumeSha256Checksum(),
                clean.targetSection(), clean.targetReference(), clean.originalText(), clean.proposedText(),
                evidenceState(evidence), evidence, existing.createdAt(), now, existing.version() + 1);
        if (!repository.updateProposal(updated, expectedVersion)) {
            throw new ResumeTailoringConflictException("stale_version");
        }
        try {
            repository.replaceEvidence(owner, proposalId, evidence);
        } catch (DuplicateKeyException duplicate) {
            throw new ResumeTailoringConflictException("duplicate_evidence_reference");
        }
        return repository.findProposal(owner, proposalId).orElseThrow(ResumeTailoringNotFoundException::new);
    }

    @Transactional
    void deleteDraft(UUID proposalId, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        repository.findProposal(owner, proposalId).orElseThrow(ResumeTailoringNotFoundException::new);
        if (!repository.deleteProposal(owner, proposalId, expectedVersion)) {
            throw new ResumeTailoringConflictException("stale_version");
        }
    }

    @Transactional(readOnly = true)
    ResumeTailoringApprovalEligibility evaluateFutureApprovalEligibility(UUID proposalId) {
        UUID owner = owner();
        ResumeTailoringProposal proposal = repository.findProposal(owner, proposalId)
                .orElseThrow(ResumeTailoringNotFoundException::new);
        ensureEvidenceBound(owner, proposal.id());
        List<String> reasons = new ArrayList<>();
        BaseResumeDocument currentSource = repository.findBaseResume(owner, proposal.sourceResumeDocumentId())
                .orElse(null);
        if (currentSource == null
                || currentSource.version() != proposal.sourceResumeVersion()
                || !currentSource.sha256Checksum().equals(proposal.sourceResumeSha256Checksum())) {
            reasons.add("source_resume_changed");
        }
        if (proposal.evidence().isEmpty()) {
            reasons.add("missing_supporting_evidence");
        }
        for (ResumeTailoringProposalEvidence evidence : proposal.evidence()) {
            if (!repository.confirmedCareerFactExists(owner, evidence.careerFactId())) {
                reasons.add("supporting_evidence_unavailable");
                break;
            }
        }
        return new ResumeTailoringApprovalEligibility(reasons.isEmpty(), reasons);
    }

    private BaseResumeDocument requireExactSourceResume(UUID owner, UUID sourceResumeDocumentId,
            long sourceResumeVersion, String sourceResumeSha256Checksum) {
        if (sourceResumeDocumentId == null || sourceResumeSha256Checksum == null) {
            throw new ResumeTailoringNotFoundException();
        }
        BaseResumeDocument source = repository.findBaseResume(owner, sourceResumeDocumentId)
                .orElseThrow(ResumeTailoringNotFoundException::new);
        if (source.version() != sourceResumeVersion || !source.sha256Checksum().equals(sourceResumeSha256Checksum)) {
            throw new ResumeTailoringNotFoundException();
        }
        return source;
    }

    private ResumeTailoringProposalInput validateInput(ResumeTailoringProposalInput input) {
        if (input == null || input.targetSection() == null) {
            throw new IllegalArgumentException("targetSection is required");
        }
        return new ResumeTailoringProposalInput(input.targetSection(),
                cleanRequired(input.targetReference(), TARGET_REFERENCE_MAX),
                cleanOptional(input.originalText(), TEXT_MAX),
                cleanRequired(input.proposedText(), TEXT_MAX),
                input.evidence() == null ? List.of() : List.copyOf(input.evidence()));
    }

    private List<ResumeTailoringProposalEvidence> evidence(
            UUID owner,
            UUID proposalId,
            List<ResumeTailoringEvidenceInput> inputs,
            Instant now) {
        if (inputs.size() > MAX_EVIDENCE_REFERENCES) {
            throw new ResumeTailoringTooLargeException();
        }
        Set<UUID> seen = new HashSet<>();
        List<ResumeTailoringProposalEvidence> evidence = new ArrayList<>();
        for (ResumeTailoringEvidenceInput input : inputs) {
            if (input == null || input.careerFactId() == null) {
                throw new ResumeTailoringNotFoundException();
            }
            if (!seen.add(input.careerFactId())) {
                throw new ResumeTailoringConflictException("duplicate_evidence_reference");
            }
            if (!repository.confirmedCareerFactExists(owner, input.careerFactId())) {
                throw new ResumeTailoringNotFoundException();
            }
            evidence.add(new ResumeTailoringProposalEvidence(UUID.randomUUID(), owner, proposalId,
                    input.careerFactId(), input.userNote(), now, now, 0));
        }
        return List.copyOf(evidence);
    }

    private void ensureEvidenceBound(UUID owner, UUID proposalId) {
        if (repository.countEvidence(owner, proposalId) > MAX_EVIDENCE_REFERENCES) {
            throw new ResumeTailoringTooLargeException();
        }
    }

    private ResumeTailoringEvidenceState evidenceState(List<ResumeTailoringProposalEvidence> evidence) {
        return evidence.isEmpty()
                ? ResumeTailoringEvidenceState.MISSING_EVIDENCE
                : ResumeTailoringEvidenceState.SUPPORTED_BY_CONFIRMED_FACTS;
    }

    private UUID owner() {
        return actors.currentActor().accountId();
    }

    private int boundedLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_PROPOSAL_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_PROPOSAL_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PROPOSAL_LIMIT);
        }
        return requestedLimit;
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("version must be nonnegative");
        }
    }

    private String cleanRequired(String value, int max) {
        String cleaned = cleanOptional(value, max);
        if (cleaned == null) {
            throw new IllegalArgumentException("value is required");
        }
        return cleaned;
    }

    private String cleanOptional(String value, int max) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip();
        if (cleaned.isEmpty() || cleaned.length() > max) {
            throw new IllegalArgumentException("invalid text length");
        }
        return cleaned;
    }
}
