package com.jobsearchassistant.documents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ResumeTailoringExportService {
    static final String POLICY = "DOCX_WHOLE_PARAGRAPH_V1";
    private final CurrentActorProvider actors;
    private final ResumeTailoringRepository proposals;
    private final ResumeTailoringExportRepository decisions;
    private final ResumeTailoringService tailoring;
    private final BaseResumeStorage storage;
    private final DocxExportEngine engine;
    private final TransactionTemplate transactions;
    private final Semaphore capacity = new Semaphore(2);

    ResumeTailoringExportService(CurrentActorProvider actors, ResumeTailoringRepository proposals,
            ResumeTailoringExportRepository decisions, ResumeTailoringService tailoring,
            BaseResumeStorage storage, PlatformTransactionManager manager, DocxExportEngine engine) {
        this.actors = actors; this.proposals = proposals; this.decisions = decisions;
        this.tailoring = tailoring; this.storage = storage; this.transactions = new TransactionTemplate(manager);
        this.engine = engine;
    }

    record TargetReview(ResumeTailoringReview review, DocxReplacementSpike.Anchor anchor, String sourceText,
            String resolvedRevision, boolean exportApproved) {
        @Override public String toString() { return "TargetReview[redacted]"; }
    }
    private record Snapshot(ResumeTailoringReview review, byte[] bytes,
            ResumeTailoringExportRepository.DecisionBinding decision) {
        @Override public String toString() { return "Snapshot[redacted]"; }
    }

    TargetReview review(UUID id, long expectedVersion) {
        return bounded(() -> {
            UUID owner = actors.currentActor().accountId();
            Snapshot snapshot = capture(owner, id, expectedVersion);
            TargetReview result = resolve(snapshot);
            return transactions.execute(status -> {
                ResumeTailoringReview current = lockedReview(owner, id, expectedVersion);
                same(snapshot.review().reviewToken(), current.reviewToken());
                return new TargetReview(result.review(), result.anchor(), result.sourceText(), result.resolvedRevision(),
                        exportApproved(owner, id, current, result.resolvedRevision()));
            });
        });
    }

    ResumeTailoringDecision approve(UUID id, long expectedVersion, String resolvedRevision, boolean attested) {
        if (!attested) throw new ResumeTailoringConflictException("attestation_required");
        return bounded(() -> {
            UUID owner = actors.currentActor().accountId();
            Snapshot snapshot = capture(owner, id, expectedVersion);
            TargetReview resolved = resolve(snapshot);
            same(resolved.resolvedRevision(), resolvedRevision);
            return transactions.execute(status -> {
                ResumeTailoringReview current = lockedReview(owner, id, expectedVersion);
                same(snapshot.review().reviewToken(), current.reviewToken());
                ResumeTailoringDecision approval = tailoring.approve(id, expectedVersion, current.reviewToken(), true);
                decisions.bind(owner, approval.id(), resolvedRevision);
                return approval;
            });
        });
    }

    byte[] export(UUID id, long expectedVersion, String resolvedRevision) {
        return bounded(() -> {
            UUID owner = actors.currentActor().accountId();
            Snapshot snapshot = capture(owner, id, expectedVersion);
            TargetReview resolved = resolve(snapshot);
            same(resolved.resolvedRevision(), resolvedRevision);
            if (snapshot.decision() == null || !"APPROVED".equals(snapshot.decision().type())
                    || !resolvedRevision.equals(snapshot.decision().revision())
                    || snapshot.review().proposal().lifecycleStatus() != ResumeTailoringLifecycleStatus.APPROVED
                    || !snapshot.review().eligibility().eligible()) throw new ResumeTailoringConflictException("export_unapproved");
            byte[] output = engine.replace(snapshot.bytes(), resolved.anchor(),
                    resolved.sourceText(), snapshot.review().proposal().proposedText());
            // No generation or XML parsing under locks. This final transaction is the release authorization point.
            transactions.executeWithoutResult(status -> {
                ResumeTailoringReview current = lockedReview(owner, id, expectedVersion);
                same(snapshot.review().reviewToken(), current.reviewToken());
                if (!exportApproved(owner, id, current, resolvedRevision)
                        || !Objects.equals(snapshot.decision(), decisions.latest(owner, id).orElse(null))) {
                    throw new ResumeTailoringConflictException("export_unapproved");
                }
            });
            return output;
        });
    }

    private Snapshot capture(UUID owner, UUID id, long expectedVersion) {
        return transactions.execute(status -> {
            ResumeTailoringReview review = lockedReview(owner, id, expectedVersion);
            BaseResumeDocument source = proposals.lockBaseResume(owner, review.proposal().sourceResumeDocumentId())
                    .orElseThrow(ResumeTailoringNotFoundException::new);
            if (!BaseResumeValidator.DOCX.equals(source.mediaType())) throw new ResumeTailoringConflictException("export_unsupported");
            try (var stream = storage.open(source.storageKey()).stream()) {
                byte[] bytes = stream.readNBytes(DocxReplacementSpike.INPUT_LIMIT + 1);
                if (bytes.length > DocxReplacementSpike.INPUT_LIMIT || bytes.length != source.byteSize()
                        || !DocxReplacementSpike.sha(bytes).equals(source.sha256Checksum())) {
                    throw new ResumeTailoringConflictException("source_resume_changed");
                }
                return new Snapshot(review, bytes, decisions.latest(owner, id).orElse(null));
            } catch (java.io.IOException failure) { throw new ResumeTailoringConflictException("source_resume_changed"); }
        });
    }

    private ResumeTailoringReview lockedReview(UUID owner, UUID id, long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("invalid_version");
        ResumeTailoringProposal proposal = proposals.lockProposal(owner, id).orElseThrow(ResumeTailoringNotFoundException::new);
        if (proposal.version() != expectedVersion) throw new ResumeTailoringConflictException("stale_version");
        if (proposals.countEvidence(owner, id) > ResumeTailoringService.MAX_EVIDENCE_REFERENCES) throw new ResumeTailoringTooLargeException();
        proposals.lockBaseResume(owner, proposal.sourceResumeDocumentId()).orElseThrow(ResumeTailoringNotFoundException::new);
        proposal.evidence().stream().map(ResumeTailoringProposalEvidence::careerFactId).sorted().forEach(fact -> {
            if (proposals.lockConfirmedCareerFactReference(owner, fact).isEmpty()) throw new ResumeTailoringConflictException("approval_ineligible");
        });
        ResumeTailoringReview review = tailoring.review(id);
        if (review.eligibility().reasons().stream().anyMatch(reason -> !reason.equals("approval_stale"))) {
            throw new ResumeTailoringConflictException("approval_ineligible");
        }
        return review;
    }

    private TargetReview resolve(Snapshot snapshot) {
        ResumeTailoringProposal proposal = snapshot.review().proposal();
        var target = engine.resolve(snapshot.bytes(), proposal.sourceResumeSha256Checksum(), proposal.originalText());
        String binding = POLICY + "|" + snapshot.review().reviewToken() + "|" + target.anchor().sourceSha256()
                + "|" + target.anchor().bodyChildIndex() + "|" + target.anchor().paragraphSha256();
        return new TargetReview(snapshot.review(), target.anchor(), target.sourceText(),
                DocxReplacementSpike.sha(binding.getBytes(StandardCharsets.UTF_8)), false);
    }
    private boolean exportApproved(UUID owner, UUID id, ResumeTailoringReview review, String revision) {
        return review.proposal().lifecycleStatus() == ResumeTailoringLifecycleStatus.APPROVED && review.eligibility().eligible()
                && decisions.latest(owner, id).filter(decision -> "APPROVED".equals(decision.type()) && revision.equals(decision.revision())).isPresent();
    }
    private static void same(String expected, String supplied) {
        if (supplied == null || supplied.length() != 64 || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResumeTailoringConflictException("stale_review");
        }
    }
    private <T> T bounded(Supplier<T> work) {
        if (!capacity.tryAcquire()) throw new ResumeTailoringConflictException("export_busy");
        try { return work.get(); } finally { capacity.release(); }
    }
}
