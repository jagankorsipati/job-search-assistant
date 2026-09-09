package com.jobsearchassistant.documents;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ResumeTailoringRepository {
    Optional<BaseResumeDocument> findBaseResume(UUID ownerAccountId, UUID resumeId);

    boolean confirmedCareerFactExists(UUID ownerAccountId, UUID careerFactId);

    void insertProposal(ResumeTailoringProposal proposal);

    void insertEvidence(ResumeTailoringProposalEvidence evidence);

    Optional<ResumeTailoringProposal> findProposal(UUID ownerAccountId, UUID proposalId);

    List<ResumeTailoringProposal> findProposals(UUID ownerAccountId, int limit);

    int countProposals(UUID ownerAccountId);

    List<ResumeTailoringProposalEvidence> findEvidence(UUID ownerAccountId, UUID proposalId, int limit);

    int countEvidence(UUID ownerAccountId, UUID proposalId);

    boolean updateProposal(ResumeTailoringProposal proposal, long expectedVersion);

    void replaceEvidence(UUID ownerAccountId, UUID proposalId, List<ResumeTailoringProposalEvidence> evidence);

    boolean deleteProposal(UUID ownerAccountId, UUID proposalId, long expectedVersion);
}
