package com.jobsearchassistant.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ResumeTailoringRepository {
    Optional<BaseResumeDocument> findBaseResume(UUID ownerAccountId, UUID resumeId);

    boolean confirmedCareerFactExists(UUID ownerAccountId, UUID careerFactId);

    Optional<ResumeTailoringFactReference> findConfirmedCareerFactReference(UUID ownerAccountId, UUID careerFactId);

    Optional<ResumeTailoringFactReference> lockConfirmedCareerFactReference(UUID ownerAccountId, UUID careerFactId);

    void insertProposal(ResumeTailoringProposal proposal);

    void insertEvidence(ResumeTailoringProposalEvidence evidence);

    Optional<ResumeTailoringProposal> findProposal(UUID ownerAccountId, UUID proposalId);

    Optional<ResumeTailoringProposal> lockProposal(UUID ownerAccountId, UUID proposalId);

    Optional<BaseResumeDocument> lockBaseResume(UUID ownerAccountId, UUID resumeId);

    List<ResumeTailoringProposalEvidence> lockEvidence(UUID ownerAccountId, UUID proposalId, int limit);

    List<ResumeTailoringProposal> findProposals(UUID ownerAccountId, int limit);

    int countProposals(UUID ownerAccountId);

    List<ResumeTailoringProposalEvidence> findEvidence(UUID ownerAccountId, UUID proposalId, int limit);

    int countEvidence(UUID ownerAccountId, UUID proposalId);

    boolean updateProposal(ResumeTailoringProposal proposal, long expectedVersion);

    boolean updateProposalLifecycle(UUID ownerAccountId, UUID proposalId, ResumeTailoringLifecycleStatus status,
            long expectedVersion);

    void replaceEvidence(UUID ownerAccountId, UUID proposalId, List<ResumeTailoringProposalEvidence> evidence);

    boolean deleteProposal(UUID ownerAccountId, UUID proposalId, long expectedVersion);

    boolean hasDecisions(UUID ownerAccountId, UUID proposalId);

    void insertDecision(ResumeTailoringDecision decision);

    Optional<ResumeTailoringDecision> findLatestApproval(UUID ownerAccountId, UUID proposalId);
}
