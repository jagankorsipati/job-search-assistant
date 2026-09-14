import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { tailoringApi, type Proposal, type ResolvedReview } from '../../api/tailoring';
import { profileApi, type CareerFact } from '../../api/profile';

export function ResolvedExportReview({
  proposal,
  disabled,
  onExpired,
  onApproved,
}: {
  proposal: Proposal;
  disabled: boolean;
  onExpired: () => void;
  onApproved: () => void;
}) {
  const [review, setReview] = useState<ResolvedReview>();
  const [facts, setFacts] = useState<CareerFact[]>([]);
  const [attested, setAttested] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState('');
  const [message, setMessage] = useState('');
  const alive = useRef(false);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const reset = () => {
    setReview(undefined);
    setFacts([]);
    setAttested(false);
  };
  const fail = (error: unknown) => {
    if (error instanceof ApiError && error.status === 401) {
      onExpired();
      return;
    }
    setFailure(
      error instanceof ApiError && error.code === 'export_unsupported'
        ? 'This document or target is unsupported. A unique whole paragraph with consistent run formatting is required in a DOCX. No file was returned.'
        : 'The source, proposal, approval, or facts may have changed, or the operation is unavailable. Load a fresh source-target review and attest again. No file was returned.',
    );
  };
  const load = async () => {
    if (busy || disabled) return;
    reset();
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const loaded = await tailoringApi.resolvedReview(proposal.id, proposal.version);
      if (!alive.current) return;
      const linked = await Promise.all(
        loaded.review.evidenceReferences.map((ref) => profileApi.getFact(ref.careerFactId)),
      );
      if (!alive.current) return;
      if (
        linked.some(
          (fact) =>
            fact.status !== 'CONFIRMED' ||
            loaded.review.evidenceReferences.find((ref) => ref.careerFactId === fact.id)
              ?.version !== fact.version,
        )
      )
        throw new ApiError(409);
      setFacts(linked);
      setReview(loaded);
    } catch (error) {
      if (alive.current) fail(error);
    } finally {
      if (alive.current) setBusy(false);
    }
  };
  const approve = async () => {
    if (!review || !attested || busy || disabled) return;
    const reviewed = review;
    reset();
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      await tailoringApi.approveResolved(
        proposal.id,
        reviewed.review.proposal.version,
        reviewed.resolvedRevision,
      );
      if (alive.current) {
        setMessage(
          'Resolved-target approval recorded. Load the source-target review again before downloading.',
        );
        onApproved();
      }
    } catch (error) {
      if (alive.current) fail(error);
    } finally {
      if (alive.current) setBusy(false);
    }
  };
  const download = async () => {
    if (!review?.exportApproved || busy || disabled) return;
    const reviewed = review;
    reset();
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const blob = await tailoringApi.export(
        proposal.id,
        reviewed.review.proposal.version,
        reviewed.resolvedRevision,
      );
      if (!alive.current) return;
      const url = URL.createObjectURL(blob);
      try {
        const link = document.createElement('a');
        link.href = url;
        link.download = 'tailored-resume.docx';
        document.body.append(link);
        try {
          link.click();
        } finally {
          link.remove();
        }
      } finally {
        URL.revokeObjectURL(url);
      }
      setMessage(
        'DOCX response received and download requested. The browser controls whether the file is saved.',
      );
    } catch (error) {
      if (alive.current) fail(error);
    } finally {
      if (alive.current) setBusy(false);
    }
  };
  return (
    <section aria-label="DOCX source-target review">
      <h3>Tailored DOCX</h3>
      <p>
        One proposal per file. The uploaded resume is unchanged. Layout fidelity remains unverified;
        inspect the downloaded document before use.
      </p>
      <button disabled={busy || disabled} onClick={() => void load()}>
        Review actual DOCX target
      </button>
      {busy && <p role="status">Checking document...</p>}
      {failure && <p role="alert">{failure}</p>}
      <p role="status" aria-live="polite">
        {message}
      </p>
      {review && (
        <>
          <p>
            Document part: {review.documentPart}; body location: {review.bodyChildIndex + 1}.
            Policy: {review.targetPolicy}
          </p>
          <p>
            Source version {review.review.proposal.sourceResume.version}; SHA-256{' '}
            {review.review.proposal.sourceResume.sha256Checksum}
          </p>
          <div className="tailoring-comparison">
            <div>
              <h4>Actual source paragraph</h4>
              <p className="tailoring-text">{review.sourceText}</p>
            </div>
            <div>
              <h4>Intended replacement</h4>
              <p className="tailoring-text">{review.review.proposal.proposedText}</p>
            </div>
          </div>
          <p>
            The source paragraph was extracted and matched exactly. This does not independently
            verify the claims. Linked facts do not establish support for every claim.
          </p>
          <ul>
            {facts.map((fact) => (
              <li key={fact.id}>
                {fact.factualContent} (version {fact.version})
              </li>
            ))}
          </ul>
          {review.exportApproved ? (
            <p>Resolved-target approval is current at this review. Download rechecks it.</p>
          ) : (
            <>
              <p>
                A fresh attestation to this actual location and wording is required. Earlier
                wording-only approval does not authorize export.
              </p>
              <label className="tailoring-check">
                <input
                  type="checkbox"
                  checked={attested}
                  disabled={busy || disabled}
                  onChange={(event) => setAttested(event.target.checked)}
                />
                <span>
                  I reviewed this exact source location and replacement, and attest that the wording
                  accurately represents my experience without unsupported claims.
                </span>
              </label>
              <button disabled={busy || disabled || !attested} onClick={() => void approve()}>
                Approve resolved change
              </button>
            </>
          )}
          <button
            disabled={busy || disabled || !review.exportApproved}
            onClick={() => void download()}
          >
            Download tailored DOCX
          </button>
          <p>
            Approval and download do not authorize application submission or independently verify
            claims.
          </p>
        </>
      )}
    </section>
  );
}
