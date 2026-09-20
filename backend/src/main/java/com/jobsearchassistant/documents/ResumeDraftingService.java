package com.jobsearchassistant.documents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jobsearchassistant.identity.api.CurrentActorProvider;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.Evidence;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.Outcome;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.Request;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.Task;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only preparation, never proposal creation, fact confirmation, approval or export. */
@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ResumeDraftingService {
    private final CurrentActorProvider actors;
    private final ResumeTailoringRepository repository;
    private final GroundedDraftingProvider provider;

    ResumeDraftingService(CurrentActorProvider actors, ResumeTailoringRepository repository,
            GroundedDraftingProvider provider) {
        this.actors = actors;
        this.repository = repository;
        this.provider = provider;
    }

    /** Selecting a proposal selects its user-supplied original paragraph, not an extracted file. */
    @Transactional(readOnly = true)
    Prepared prepare(UUID proposalId, long expectedVersion, List<ResumeTailoringFactReference> selectedFacts) {
        if (expectedVersion < 0 || selectedFacts == null || selectedFacts.isEmpty()
                || selectedFacts.size() > GroundedDraftingProvider.FACT_COUNT_MAX) {
            throw new IllegalArgumentException("invalid_drafting_selection");
        }
        UUID owner = actors.currentActor().accountId();
        ResumeTailoringProposal proposal = repository.findProposal(owner, proposalId)
                .orElseThrow(ResumeTailoringNotFoundException::new);
        if (proposal.version() != expectedVersion) throw stale();
        requireSource(owner, proposal.sourceResumeDocumentId(), proposal.sourceResumeVersion(),
                proposal.sourceResumeSha256Checksum());
        Map<String, ResumeTailoringFactReference> aliases = new LinkedHashMap<>();
        List<Evidence> evidence = new ArrayList<>();
        var seen = new java.util.HashSet<UUID>();
        for (ResumeTailoringFactReference selected : selectedFacts) {
            if (selected == null || selected.careerFactId() == null || selected.version() < 0
                    || !seen.add(selected.careerFactId())) throw new IllegalArgumentException("invalid_drafting_selection");
            ResumeDraftingFact fact = repository.findConfirmedDraftingFact(owner, selected.careerFactId())
                    .orElseThrow(ResumeTailoringNotFoundException::new);
            if (fact.version() != selected.version()) throw stale();
            String alias = "E" + (evidence.size() + 1);
            aliases.put(alias, selected);
            evidence.add(new Evidence(alias, fact.content()));
        }
        Request request = new Request(Task.REWORD_PARAGRAPH, proposal.originalText(), evidence);
        return new Prepared(request, new Binding(owner, proposal.id(), proposal.version(), proposal.updatedAt(),
                proposal.lifecycleStatus(), proposal.sourceResumeDocumentId(), proposal.sourceResumeVersion(),
                proposal.sourceResumeSha256Checksum(), aliases));
    }

    /** No production caller or live provider exists. Future transmission requires preview/consent first. */
    Outcome suggest(Prepared prepared) {
        requireCurrent(prepared);
        if (Thread.currentThread().isInterrupted()) return GroundedDraftingProvider.Failure.CANCELLED;
        Outcome result;
        try {
            result = GroundedDraftingProvider.validate(prepared.request, provider.suggest(prepared.request));
        } catch (RuntimeException failure) {
            // Provider exceptions may contain selected text or transport credentials. Never retain/log them.
            result = GroundedDraftingProvider.Failure.UNAVAILABLE;
        }
        if (Thread.currentThread().isInterrupted()) return GroundedDraftingProvider.Failure.CANCELLED;
        requireCurrent(prepared);
        return result;
    }

    /** Read-only stale check, not transactional import authorization. Future import must lock/recheck. */
    @Transactional(readOnly = true)
    void requireCurrent(Prepared prepared) {
        UUID owner = actors.currentActor().accountId();
        if (prepared == null || !owner.equals(prepared.binding.owner())) throw new ResumeTailoringNotFoundException();
        Binding binding = prepared.binding;
        ResumeTailoringProposal proposal = repository.findProposal(owner, binding.proposal())
                .orElseThrow(ResumeTailoringNotFoundException::new);
        if (proposal.version() != binding.proposalVersion() || !proposal.updatedAt().equals(binding.updatedAt())
                || proposal.lifecycleStatus() != binding.status()
                || !proposal.sourceResumeDocumentId().equals(binding.source())
                || proposal.sourceResumeVersion() != binding.sourceVersion()
                || !proposal.sourceResumeSha256Checksum().equals(binding.checksum())) throw stale();
        requireSource(owner, binding.source(), binding.sourceVersion(), binding.checksum());
        for (ResumeTailoringFactReference reference : binding.aliases().values()) {
            ResumeDraftingFact fact = repository.findConfirmedDraftingFact(owner, reference.careerFactId())
                    .orElseThrow(ResumeTailoringNotFoundException::new);
            if (fact.version() != reference.version()) throw stale();
        }
    }

    private void requireSource(UUID owner, UUID id, long version, String checksum) {
        BaseResumeDocument source = repository.findBaseResume(owner, id)
                .orElseThrow(ResumeTailoringNotFoundException::new);
        if (source.version() != version || !source.sha256Checksum().equals(checksum)) throw stale();
    }

    private static ResumeTailoringConflictException stale() {
        return new ResumeTailoringConflictException("stale_drafting_context");
    }

    static final class Prepared {
        private final Request request;
        private final Binding binding;
        private Prepared(Request request, Binding binding) { this.request = request; this.binding = binding; }
        Request request() { return request; }
        Map<String, ResumeTailoringFactReference> aliases() { return binding.aliases(); }
        @Override public String toString() { return "PreparedDrafting[redacted]"; }
    }

    private record Binding(UUID owner, UUID proposal, long proposalVersion, Instant updatedAt,
            ResumeTailoringLifecycleStatus status, UUID source, long sourceVersion, String checksum,
            Map<String, ResumeTailoringFactReference> aliases) {
        private Binding { aliases = Map.copyOf(aliases); }
        @Override public String toString() { return "DraftingBinding[redacted]"; }
    }
}
