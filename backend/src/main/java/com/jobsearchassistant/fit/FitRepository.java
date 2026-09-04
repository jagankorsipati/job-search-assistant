package com.jobsearchassistant.fit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FitRepository {
    boolean ownedSnapshotExists(UUID ownerAccountId, UUID jobId, UUID snapshotId);

    List<JobRequirement> findRequirements(UUID ownerAccountId, UUID jobId, UUID snapshotId, int limit);

    Optional<JobRequirement> findRequirement(UUID requirementId, UUID ownerAccountId);

    void insertRequirement(JobRequirement requirement);

    boolean updateRequirement(JobRequirement requirement, long expectedVersion);

    boolean deleteRequirement(UUID requirementId, UUID ownerAccountId, long expectedVersion);

    List<CandidateEvidenceLink> findEvidenceLinks(UUID ownerAccountId, UUID requirementId, int limit);

    List<CandidateEvidenceLink> findEvidenceLinksForSnapshot(UUID ownerAccountId, UUID jobId, UUID snapshotId, int limit);

    Optional<CandidateEvidenceLink> findEvidenceLink(UUID linkId, UUID ownerAccountId);

    void insertEvidenceLink(CandidateEvidenceLink link);

    boolean updateEvidenceLink(CandidateEvidenceLink link, long expectedVersion);

    boolean deleteEvidenceLink(UUID linkId, UUID ownerAccountId, long expectedVersion);

    boolean confirmedCareerFactExists(UUID ownerAccountId, UUID factId);

    boolean profileExists(UUID ownerAccountId);

    boolean baseResumeExists(UUID ownerAccountId, UUID resumeId);
}
