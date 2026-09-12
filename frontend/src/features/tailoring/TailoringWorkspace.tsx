import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { documentsApi } from '../../api/documents';
import { profileApi, type CareerFact } from '../../api/profile';
import {
  tailoringApi,
  targetSections,
  type Proposal,
  type ProposalFields,
  type ProposalReview,
  type SourceResume,
} from '../../api/tailoring';

const blank: ProposalFields = {
  targetSection: 'SUMMARY',
  targetReference: '',
  originalText: null,
  proposedText: '',
  evidence: [],
};
const reasons: Record<string, string> = {
  source_resume_changed:
    'The source resume has changed or is unavailable. Create a new proposal against the current resume.',
  missing_supporting_evidence: 'No supporting evidence is linked.',
  supporting_evidence_unavailable:
    'Linked evidence is no longer available as a confirmed career fact.',
  approval_stale:
    'Historical approval is stale. Review the current wording and facts before attesting again.',
};
function safeError(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 'decision_history_exists')
      return 'Deletion refused: approval or rejection history must be preserved.';
    if (error.code === 'tailoring_too_large')
      return 'The proposal collection exceeds the 100-item limit. No partial list is shown.';
    if (error.status === 409)
      return 'The proposal, source, or evidence changed or is ineligible. Unsaved input is preserved. Load a fresh review and attest again before approval.';
    if (error.status === 404)
      return 'The proposal, source resume, or selected evidence is unavailable.';
    if (error.status === 403)
      return 'The action was refused. Refresh your session before trying again.';
    if (error.status === 400)
      return 'Check the fields and selected evidence. The change was not saved.';
  }
  return 'The request could not be completed. Try again.';
}

export function TailoringWorkspace({ onExpired }: { onExpired: () => void }) {
  const [items, setItems] = useState<Proposal[]>([]);
  const [selected, setSelected] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState('');
  const [refresh, setRefresh] = useState(0);
  const listGeneration = useRef(0);
  useEffect(() => {
    let active = true;
    const ticket = ++listGeneration.current;
    tailoringApi
      .list()
      .then((values) => {
        if (active && listGeneration.current === ticket) {
          setItems(values);
          setFailure('');
        }
      })
      .catch((error: unknown) => {
        if (active && listGeneration.current === ticket) {
          if (error instanceof ApiError && error.status === 401) onExpired();
          else setFailure(safeError(error));
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [refresh, onExpired]);
  return (
    <section className="tailoring-workspace" aria-labelledby="tailoring-title">
      <a href="/profile">Back to profile and base resume</a>
      <h1 id="tailoring-title">Resume tailoring proposals</h1>
      {failure && <p role="alert">{failure}</p>}
      <div className="form-actions">
        <button onClick={() => setSelected('new')}>New proposal</button>
        <button
          onClick={() => {
            setLoading(true);
            setRefresh((value) => value + 1);
          }}
        >
          Refresh list
        </button>
      </div>
      {loading && <p role="status">Loading proposals...</p>}
      {!loading && !failure && items.length === 0 && <p>No tailoring proposals yet.</p>}
      <div className="tailoring-layout">
        <nav aria-label="Tailoring proposals">
          <ul className="tailoring-list">
            {items.map((item) => (
              <li key={item.id}>
                <button
                  aria-current={selected === item.id ? 'true' : undefined}
                  onClick={() => setSelected(item.id)}
                >
                  {item.targetReference}{' '}
                  <span>
                    {item.lifecycleStatus === 'APPROVED'
                      ? 'Approval recorded'
                      : item.lifecycleStatus === 'REJECTED'
                        ? 'Rejected'
                        : 'Draft'}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </nav>
        {selected && (
          <ProposalPanel
            key={selected}
            id={selected}
            onExpired={onExpired}
            onSaved={(proposal) => {
              listGeneration.current++;
              setItems((current) => [
                proposal,
                ...current.filter((item) => item.id !== proposal.id),
              ]);
              setSelected(proposal.id);
            }}
            onRemoved={() => {
              setSelected(undefined);
              setRefresh((value) => value + 1);
            }}
          />
        )}
      </div>
    </section>
  );
}

function ProposalPanel({
  id,
  onExpired,
  onSaved,
  onRemoved,
}: {
  id: string;
  onExpired: () => void;
  onSaved: (proposal: Proposal) => void;
  onRemoved: () => void;
}) {
  const [proposal, setProposal] = useState<Proposal>();
  const [form, setForm] = useState<ProposalFields>(blank);
  const [source, setSource] = useState<SourceResume>();
  const [facts, setFacts] = useState<CareerFact[]>([]);
  const [reviewFacts, setReviewFacts] = useState<CareerFact[]>([]);
  const [review, setReview] = useState<ProposalReview>();
  const [attested, setAttested] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(true);
  const [failure, setFailure] = useState('');
  const [message, setMessage] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [reload, setReload] = useState(0);
  const generation = useRef(0);
  const expired = useRef(onExpired);
  useEffect(() => {
    expired.current = onExpired;
  }, [onExpired]);
  const fail = (error: unknown) => {
    if (error instanceof ApiError && error.status === 401) expired.current();
    else setFailure(safeError(error));
  };
  useEffect(() => {
    const ticket = ++generation.current;
    const load = async () => {
      try {
        const confirmed = await profileApi.listFacts({ status: 'CONFIRMED', limit: 100 });
        if (generation.current !== ticket) return;
        setFacts(confirmed.filter((fact) => fact.status === 'CONFIRMED'));
        if (id === 'new') {
          const resume = await documentsApi.getBaseResume();
          if (generation.current !== ticket) return;
          if (!resume.sha256Checksum) throw new Error('Source attribution unavailable');
          setSource({
            documentId: resume.id,
            version: resume.version,
            sha256Checksum: resume.sha256Checksum,
          });
        } else {
          const loaded = await tailoringApi.get(id);
          if (generation.current !== ticket) return;
          setProposal(loaded);
          setSource(loaded.sourceResume);
          setForm(loaded);
          setDirty(false);
        }
      } catch (error) {
        if (generation.current === ticket) {
          if (error instanceof ApiError && error.status === 401) expired.current();
          else setFailure(safeError(error));
        }
      } finally {
        if (generation.current === ticket) setBusy(false);
      }
    };
    void load();
    return () => {
      generation.current = ticket + 1;
    };
  }, [id, reload]);
  const invalidate = () => {
    setReview(undefined);
    setReviewFacts([]);
    setAttested(false);
    setMessage('');
  };
  const change = (next: ProposalFields) => {
    invalidate();
    setForm(next);
    setDirty(true);
  };
  const save = async (event: FormEvent) => {
    event.preventDefault();
    if (!source || busy) return;
    const ticket = generation.current;
    invalidate();
    setBusy(true);
    setFailure('');
    try {
      const saved = proposal
        ? await tailoringApi.update(proposal.id, form, proposal.version)
        : await tailoringApi.create(form, source);
      if (generation.current !== ticket) return;
      setProposal(saved);
      setForm(saved);
      setDirty(false);
      setMessage('Draft saved. A fresh review and attestation are required for approval.');
      onSaved(saved);
    } catch (error) {
      if (generation.current === ticket) fail(error);
    } finally {
      if (generation.current === ticket) setBusy(false);
    }
  };
  const loadReview = async () => {
    if (!proposal || busy || dirty) return;
    const ticket = generation.current;
    invalidate();
    setBusy(true);
    setFailure('');
    try {
      const result = await tailoringApi.review(proposal.id);
      if (generation.current !== ticket) return;
      // Fetch exactly the linked facts, even when they are outside the selection list.
      const linked = await Promise.all(
        result.evidenceReferences.map((ref) => profileApi.getFact(ref.careerFactId)),
      );
      if (generation.current !== ticket) return;
      if (
        linked.some(
          (fact) =>
            fact.status !== 'CONFIRMED' ||
            result.evidenceReferences.find((ref) => ref.careerFactId === fact.id)?.version !==
              fact.version,
        )
      )
        throw new ApiError(409, 'stale_review');
      setReviewFacts(linked);
      setReview(result);
      setProposal(result.proposal);
      setForm(result.proposal);
      setSource(result.proposal.sourceResume);
    } catch (error) {
      if (generation.current === ticket) fail(error);
    } finally {
      if (generation.current === ticket) setBusy(false);
    }
  };
  const decide = async (action: 'approve' | 'reject' | 'delete') => {
    if (!proposal || busy || dirty || (action === 'approve' && (!review || !attested))) return;
    const ticket = generation.current;
    const reviewed = review;
    invalidate();
    setBusy(true);
    setFailure('');
    try {
      if (action === 'delete') {
        await tailoringApi.remove(proposal.id, proposal.version);
        if (generation.current === ticket) onRemoved();
      } else {
        const decision =
          action === 'approve' && reviewed
            ? await tailoringApi.approve(
                proposal.id,
                reviewed.proposal.version,
                reviewed.reviewRevision,
              )
            : await tailoringApi.reject(proposal.id, proposal.version);
        if (generation.current !== ticket) return;
        const updated = { ...proposal, lifecycleStatus: decision.decisionType };
        setProposal(updated);
        onSaved(updated);
        setMessage(
          action === 'approve'
            ? 'Approval attestation recorded. Refresh review to evaluate its current validity.'
            : 'Proposal rejected. This is not a judgment of candidate qualifications.',
        );
      }
    } catch (error) {
      if (generation.current === ticket) fail(error);
    } finally {
      if (generation.current === ticket) {
        setBusy(false);
        setDeleting(false);
      }
    }
  };
  const canApprove =
    review && review.eligibility.reasons.every((reason) => reason === 'approval_stale');
  return (
    <section className="tailoring-detail" aria-label="Proposal detail">
      <h2>{id === 'new' ? 'New proposal' : 'Proposal detail'}</h2>
      {busy && <p role="status">Loading or saving proposal...</p>}
      {failure && <p role="alert">{failure}</p>}
      {failure && (!source || id === 'new') && (
        <button
          disabled={busy}
          onClick={() => {
            setFailure('');
            setBusy(true);
            setReload((value) => value + 1);
          }}
        >
          Retry proposal loading
        </button>
      )}
      <p role="status" aria-live="polite">
        {message}
      </p>
      {proposal && (
        <p>
          Decision:{' '}
          {proposal.lifecycleStatus === 'APPROVED'
            ? review?.eligibility.eligible
              ? 'Approval current at last review'
              : 'Approval recorded; current validity not established'
            : proposal.lifecycleStatus === 'REJECTED'
              ? 'Rejected'
              : 'Draft'}
        </p>
      )}
      {source && (
        <dl className="tailoring-source">
          <dt>Source resume document</dt>
          <dd>{source.documentId}</dd>
          <dt>Source version</dt>
          <dd>{source.version}</dd>
          <dt>SHA-256</dt>
          <dd>{source.sha256Checksum}</dd>
        </dl>
      )}
      {proposal && !review && (
        <p>Current eligibility has not been evaluated. Load a fresh review before approval.</p>
      )}
      <form aria-label="Tailoring proposal" onSubmit={(event) => void save(event)}>
        <fieldset disabled={busy || !source}>
          <legend>Manual proposal</legend>
          <label>
            Target section
            <select
              value={form.targetSection}
              onChange={(event) =>
                change({
                  ...form,
                  targetSection: event.target.value as ProposalFields['targetSection'],
                })
              }
            >
              {targetSections.map((section) => (
                <option key={section}>{section}</option>
              ))}
            </select>
          </label>
          <label>
            Target reference
            <input
              required
              maxLength={200}
              value={form.targetReference}
              onChange={(event) => change({ ...form, targetReference: event.target.value })}
            />
          </label>
          <label>
            Original text (optional, user-supplied)
            <textarea
              maxLength={4000}
              value={form.originalText ?? ''}
              onChange={(event) => change({ ...form, originalText: event.target.value })}
            />
          </label>
          <p>Original text is user-supplied and has not been checked against the document.</p>
          <label>
            Proposed text
            <textarea
              required
              maxLength={4000}
              value={form.proposedText}
              onChange={(event) => change({ ...form, proposedText: event.target.value })}
            />
          </label>
          <fieldset>
            <legend>Supporting confirmed career facts</legend>
            <p>
              Linked facts do not establish support for every proposed claim. Select only facts you
              consider supporting evidence.
            </p>
            {facts.length === 100 && (
              <p role="status">
                Showing up to 100 confirmed facts. The selection list may be incomplete.
              </p>
            )}
            {!facts.length && <p>No confirmed career facts available.</p>}
            {facts.map((fact) => (
              <label className="tailoring-check" key={fact.id}>
                <input
                  type="checkbox"
                  checked={form.evidence.some((link) => link.careerFactId === fact.id)}
                  disabled={
                    form.evidence.length >= 25 &&
                    !form.evidence.some((link) => link.careerFactId === fact.id)
                  }
                  onChange={(event) =>
                    change({
                      ...form,
                      evidence: event.target.checked
                        ? [...form.evidence, { careerFactId: fact.id, userNote: null }]
                        : form.evidence.filter((link) => link.careerFactId !== fact.id),
                    })
                  }
                />
                <span>
                  {fact.factualContent} (version {fact.version})
                </span>
              </label>
            ))}
            {form.evidence
              .filter((link) => !facts.some((fact) => fact.id === link.careerFactId))
              .map((link) => (
                <label className="tailoring-check" key={link.careerFactId}>
                  <input
                    type="checkbox"
                    checked
                    onChange={() =>
                      change({
                        ...form,
                        evidence: form.evidence.filter(
                          (item) => item.careerFactId !== link.careerFactId,
                        ),
                      })
                    }
                  />
                  <span>Linked fact {link.careerFactId}: unavailable in selection list</span>
                </label>
              ))}
            {form.evidence.map((link) => (
              <label key={link.careerFactId}>
                Support note for fact {link.careerFactId} (optional)
                <textarea
                  maxLength={1000}
                  value={link.userNote ?? ''}
                  onChange={(event) =>
                    change({
                      ...form,
                      evidence: form.evidence.map((item) =>
                        item.careerFactId === link.careerFactId
                          ? { ...item, userNote: event.target.value || null }
                          : item,
                      ),
                    })
                  }
                />
              </label>
            ))}
            {!form.evidence.length && <p>Missing supporting evidence. Approval is unavailable.</p>}
          </fieldset>
          {proposal?.lifecycleStatus === 'APPROVED' && (
            <p>Saving edits invalidates current approval. Review and approve again after saving.</p>
          )}
          <button type="submit">Save draft</button>
        </fieldset>
      </form>
      {proposal && (
        <div className="form-actions">
          <button disabled={busy || dirty} onClick={() => void loadReview()}>
            Load fresh review
          </button>
          <button disabled={busy || dirty} onClick={() => void decide('reject')}>
            Reject proposal
          </button>
          <button disabled={busy || dirty} onClick={() => setDeleting(true)}>
            Delete proposal
          </button>
          <button
            disabled={busy}
            onClick={() => {
              if (!dirty || window.confirm('Discard unsaved changes and reload?')) {
                invalidate();
                setBusy(true);
                setFailure('');
                setReload((value) => value + 1);
              }
            }}
          >
            Reload saved proposal
          </button>
        </div>
      )}
      {dirty && <p>Unsaved changes. Save before reviewing or making a decision.</p>}
      {deleting && (
        <div role="alert">
          <p>
            Delete this proposal permanently? Proposals with decision history cannot be deleted.
          </p>
          <button disabled={busy} onClick={() => void decide('delete')}>
            Confirm deletion
          </button>
          <button disabled={busy} onClick={() => setDeleting(false)}>
            Cancel deletion
          </button>
        </div>
      )}
      {review && (
        <section aria-label="Before and after review">
          <h3>Before and after</h3>
          <p>{review.originalTextNotice}</p>
          <div className="tailoring-comparison">
            <div>
              <h4>Original (user-supplied)</h4>
              <p className="tailoring-text">{review.proposal.originalText ?? 'Not supplied'}</p>
            </div>
            <div>
              <h4>Proposed</h4>
              <p className="tailoring-text">{review.proposal.proposedText}</p>
            </div>
          </div>
          <h4>Linked facts reviewed</h4>
          <ul>
            {reviewFacts.map((fact) => (
              <li key={fact.id}>
                <p className="tailoring-text">{fact.factualContent}</p>
                <p>
                  Fact {fact.id}, version {fact.version}
                </p>
                {review.proposal.evidence.find((link) => link.careerFactId === fact.id)
                  ?.userNote && (
                  <p>
                    {
                      review.proposal.evidence.find((link) => link.careerFactId === fact.id)
                        ?.userNote
                    }
                  </p>
                )}
              </li>
            ))}
          </ul>
          <p>{review.evidenceNotice}</p>
          <p>
            Eligibility:{' '}
            {review.eligibility.eligible ? 'Eligible at this review' : 'Review required'}
          </p>
          <ul>
            {review.eligibility.reasons.map((reason) => (
              <li key={reason}>
                {reasons[reason] ?? 'Approval is unavailable. Refresh the review.'}
              </li>
            ))}
          </ul>
          <p>{review.approvalNotice}</p>
          <label className="tailoring-check">
            <input
              type="checkbox"
              checked={attested}
              disabled={busy || !canApprove}
              onChange={(event) => setAttested(event.target.checked)}
            />
            <span>
              I reviewed the proposed wording and attest that it accurately represents my experience
              without unsupported claims.
            </span>
          </label>
          <button
            disabled={busy || !canApprove || !attested}
            onClick={() => void decide('approve')}
          >
            Approve proposal
          </button>
        </section>
      )}
    </section>
  );
}
