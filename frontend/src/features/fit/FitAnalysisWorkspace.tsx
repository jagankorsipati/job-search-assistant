import { type FormEvent, type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError } from '../../api/client';
import { documentsApi, type BaseResumeMetadata } from '../../api/documents';
import {
  evidenceRelationships,
  fitApi,
  profileEvidenceFields,
  requirementCategories,
  requirementImportances,
  requirementStatuses,
  type CandidateEvidenceLink,
  type EvidenceRelationship,
  type EvidenceType,
  type FitAnalysis,
  type FitFinding,
  type ImportanceBreakdown,
  type JobRequirement,
  type RequirementAssessment,
  type RequirementAssessmentResponse,
  type RequirementCategory,
  type RequirementFields,
  type RequirementImportance,
  type RequirementStatus,
} from '../../api/fit';
import { type CapturedJob, type JobDescriptionSnapshot } from '../../api/jobs';
import { profileApi, type CandidateProfile, type CareerFact } from '../../api/profile';

const blankRequirement: RequirementForm = {
  category: 'SKILL',
  importance: 'UNSPECIFIED',
  requirementText: '',
  sourceExcerpt: '',
  status: 'DRAFT',
};

const requirementLimits = {
  requirementText: 1000,
  sourceExcerpt: 4000,
};

interface RequirementForm {
  category: RequirementCategory;
  importance: RequirementImportance;
  requirementText: string;
  sourceExcerpt: string;
  status: RequirementStatus;
}

interface EvidenceCandidate {
  type: EvidenceType;
  id: string;
  label: string;
  detail: string;
  group: string;
}

interface EvidenceForm {
  evidenceType: EvidenceType | '';
  evidenceId: string;
  relationship: EvidenceRelationship | '';
  userNote: string;
}

interface AssessmentFilters {
  importance: RequirementImportance | '';
  assessment: RequirementAssessment | '';
  category: RequirementCategory | '';
}

export function FitAnalysisWorkspace({
  job,
  snapshot,
  onBack,
  onExpired,
}: {
  job: CapturedJob;
  snapshot: JobDescriptionSnapshot;
  onBack: () => void;
  onExpired: () => void;
}) {
  const [requirements, setRequirements] = useState<JobRequirement[]>([]);
  const [linksByRequirement, setLinksByRequirement] = useState<
    Record<string, CandidateEvidenceLink[]>
  >({});
  const [analysis, setAnalysis] = useState<FitAnalysis>();
  const [profile, setProfile] = useState<CandidateProfile>();
  const [facts, setFacts] = useState<CareerFact[]>([]);
  const [resume, setResume] = useState<BaseResumeMetadata>();
  const [loading, setLoading] = useState(true);
  const [refreshingAnalysis, setRefreshingAnalysis] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [failure, setFailure] = useState('');
  const [conflict, setConflict] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [requirementForm, setRequirementForm] = useState<RequirementForm>(blankRequirement);
  const [requirementErrors, setRequirementErrors] = useState<Record<string, string>>({});
  const [editingRequirementId, setEditingRequirementId] = useState<string>();
  const [evidenceRequirementId, setEvidenceRequirementId] = useState<string>();
  const [editingLinkId, setEditingLinkId] = useState<string>();
  const [evidenceForm, setEvidenceForm] = useState<EvidenceForm>({
    evidenceType: '',
    evidenceId: '',
    relationship: '',
    userNote: '',
  });
  const [filters, setFilters] = useState<AssessmentFilters>({
    importance: '',
    assessment: '',
    category: '',
  });

  const evidenceCandidates = useMemo(
    () => buildEvidenceCandidates(profile, facts, resume),
    [facts, profile, resume],
  );

  const loadEvidenceSources = useCallback(async () => {
    const [loadedProfile, loadedFacts, loadedResume] = await Promise.all([
      profileApi.getProfile().catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 404) return undefined;
        throw error;
      }),
      profileApi.listFacts({ status: 'CONFIRMED', limit: 100 }),
      documentsApi.getBaseResume().catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 404) return undefined;
        throw error;
      }),
    ]);
    setProfile(loadedProfile);
    setFacts(loadedFacts.filter((fact) => fact.status === 'CONFIRMED'));
    setResume(loadedResume);
  }, []);

  const loadRequirements = useCallback(async () => {
    const loaded = await fitApi.listRequirements(job.id, snapshot.id, 100);
    setRequirements(loaded);
    const entries = await Promise.all(
      loaded.map(
        async (requirement) =>
          [requirement.id, await fitApi.listEvidenceLinks(requirement.id, 100)] as const,
      ),
    );
    setLinksByRequirement(Object.fromEntries(entries));
    return loaded;
  }, [job.id, snapshot.id]);

  const refreshAnalysis = useCallback(
    async (preserveExisting = true) => {
      setRefreshingAnalysis(preserveExisting);
      setFailure('');
      try {
        setAnalysis(await fitApi.getAnalysis(job.id, snapshot.id));
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) onExpired();
        else if (error instanceof ApiError && error.code === 'analysis_too_large')
          setFailure(
            'This snapshot has too many reviewed inputs to calculate bounded analysis safely.',
          );
        else setFailure(readableError(error, 'Fit analysis is temporarily unavailable.'));
      } finally {
        setRefreshingAnalysis(false);
      }
    },
    [job.id, onExpired, snapshot.id],
  );

  const loadAll = useCallback(async () => {
    setLoading(true);
    setFailure('');
    setConflict(false);
    try {
      await Promise.all([loadEvidenceSources(), loadRequirements()]);
      await refreshAnalysis(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onExpired();
      else setFailure(readableError(error, 'Fit review data is temporarily unavailable.'));
    } finally {
      setLoading(false);
    }
  }, [loadEvidenceSources, loadRequirements, onExpired, refreshAnalysis]);

  useEffect(() => {
    queueMicrotask(() => void loadAll());
  }, [loadAll]);

  const saveRequirement = async (event: FormEvent) => {
    event.preventDefault();
    const validation = validateRequirement(requirementForm);
    setRequirementErrors(validation);
    if (Object.keys(validation).length > 0) return;
    const existing = requirements.find((requirement) => requirement.id === editingRequirementId);
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved =
        existing && editingRequirementId
          ? await fitApi.updateRequirement(editingRequirementId, {
              ...cleanRequirement(requirementForm),
              expectedVersion: existing.version,
            })
          : await fitApi.createRequirement(job.id, snapshot.id, cleanRequirement(requirementForm));
      setRequirements((current) =>
        current.some((requirement) => requirement.id === saved.id)
          ? current.map((requirement) => (requirement.id === saved.id ? saved : requirement))
          : [...current, saved],
      );
      setRequirementForm(blankRequirement);
      setEditingRequirementId(undefined);
      setCreateOpen(false);
      setConflict(false);
      setMessage(existing ? 'Requirement saved.' : 'Draft requirement added.');
      await loadRequirements();
      await refreshAnalysis();
    } catch (error) {
      handleWriteFailure(
        error,
        onExpired,
        setFailure,
        setConflict,
        'The requirement was not saved.',
      );
    } finally {
      setBusy(false);
    }
  };

  const editRequirement = (requirement: JobRequirement) => {
    setEditingRequirementId(requirement.id);
    setCreateOpen(false);
    setRequirementForm(requirementToForm(requirement));
    setRequirementErrors({});
    setFailure('');
  };

  const changeRequirementStatus = async (
    requirement: JobRequirement,
    status: RequirementStatus,
  ) => {
    if (status === 'CONFIRMED' && !window.confirm('Confirm this requirement for analysis?')) return;
    if (
      status === 'REJECTED' &&
      !window.confirm('Reject this requirement and exclude it from analysis?')
    )
      return;
    if (status === 'DRAFT' && !window.confirm('Return this requirement to draft?')) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      await fitApi.updateRequirement(requirement.id, {
        category: requirement.category,
        importance: requirement.importance,
        requirementText: requirement.requirementText,
        sourceExcerpt: requirement.sourceExcerpt ?? null,
        status,
        expectedVersion: requirement.version,
      });
      setMessage(
        status === 'CONFIRMED'
          ? 'Requirement confirmed.'
          : status === 'REJECTED'
            ? 'Requirement rejected.'
            : 'Requirement returned to draft.',
      );
      await loadRequirements();
      await refreshAnalysis();
    } catch (error) {
      handleWriteFailure(
        error,
        onExpired,
        setFailure,
        setConflict,
        'The requirement status was not changed.',
      );
    } finally {
      setBusy(false);
    }
  };

  const deleteRequirement = async (requirement: JobRequirement) => {
    const count = linksByRequirement[requirement.id]?.length ?? 0;
    if (
      !window.confirm(
        count > 0
          ? 'Delete this requirement? Linked evidence may prevent deletion and will not be removed automatically.'
          : 'Delete this requirement?',
      )
    )
      return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      await fitApi.deleteRequirement(requirement.id, requirement.version);
      setMessage('Requirement deleted.');
      await loadRequirements();
      await refreshAnalysis();
    } catch (error) {
      handleWriteFailure(
        error,
        onExpired,
        setFailure,
        setConflict,
        error instanceof ApiError && error.status === 409
          ? 'This requirement still has linked evidence. Remove the links first, then delete it.'
          : 'The requirement was not deleted.',
      );
    } finally {
      setBusy(false);
    }
  };

  const startEvidenceLink = (requirement: JobRequirement) => {
    setEvidenceRequirementId(requirement.id);
    setEditingLinkId(undefined);
    setEvidenceForm({ evidenceType: '', evidenceId: '', relationship: '', userNote: '' });
    setFailure('');
  };

  const editEvidenceLink = (link: CandidateEvidenceLink) => {
    setEvidenceRequirementId(link.jobRequirementId);
    setEditingLinkId(link.id);
    setEvidenceForm({
      evidenceType: link.evidenceType,
      evidenceId: link.evidenceId,
      relationship: link.relationship,
      userNote: link.userNote ?? '',
    });
    setFailure('');
  };

  const saveEvidenceLink = async (event: FormEvent) => {
    event.preventDefault();
    if (!evidenceRequirementId) return;
    if (!evidenceForm.evidenceType || !evidenceForm.evidenceId || !evidenceForm.relationship) {
      setFailure('Choose evidence and exactly one relationship before saving the link.');
      return;
    }
    const existing = Object.values(linksByRequirement)
      .flat()
      .find((link) => link.id === editingLinkId);
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      if (existing && editingLinkId) {
        await fitApi.updateEvidenceLink(editingLinkId, {
          evidenceType: evidenceForm.evidenceType,
          evidenceId: evidenceForm.evidenceId,
          relationship: evidenceForm.relationship,
          userNote: optional(evidenceForm.userNote),
          expectedVersion: existing.version,
        });
      } else {
        await fitApi.createEvidenceLink(evidenceRequirementId, {
          evidenceType: evidenceForm.evidenceType,
          evidenceId: evidenceForm.evidenceId,
          relationship: evidenceForm.relationship,
          userNote: optional(evidenceForm.userNote),
        });
      }
      setEvidenceRequirementId(undefined);
      setEditingLinkId(undefined);
      setEvidenceForm({ evidenceType: '', evidenceId: '', relationship: '', userNote: '' });
      setMessage(existing ? 'Evidence link saved.' : 'Evidence link added.');
      await loadRequirements();
      await refreshAnalysis();
    } catch (error) {
      handleWriteFailure(
        error,
        onExpired,
        setFailure,
        setConflict,
        error instanceof ApiError && error.status === 409
          ? 'That evidence link conflicts with the current server state. Your selected evidence and note are still here.'
          : 'The evidence link was not saved.',
      );
    } finally {
      setBusy(false);
    }
  };

  const removeEvidenceLink = async (link: CandidateEvidenceLink) => {
    if (!window.confirm('Remove this evidence link?')) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      await fitApi.deleteEvidenceLink(link.id, link.version);
      setMessage('Evidence link removed.');
      await loadRequirements();
      await refreshAnalysis();
    } catch (error) {
      handleWriteFailure(
        error,
        onExpired,
        setFailure,
        setConflict,
        'The evidence link was not removed.',
      );
    } finally {
      setBusy(false);
    }
  };

  const filteredAssessments = useMemo(
    () =>
      (analysis?.requirementAssessments ?? []).filter(
        (assessment) =>
          (!filters.importance || assessment.importance === filters.importance) &&
          (!filters.assessment || assessment.assessment === filters.assessment) &&
          (!filters.category || assessment.requirementCategory === filters.category),
      ),
    [analysis, filters],
  );

  if (loading) return <p>Loading fit review...</p>;

  return (
    <section className="fit-workspace" aria-labelledby="fit-title">
      <div className="section-heading">
        <p className="eyebrow">Fit review</p>
        <h1 id="fit-title">Review fit for this snapshot</h1>
        <p>
          This analysis uses only requirements you confirmed and evidence relationships you
          selected. It does not predict hiring decisions or verify qualifications independently.
        </p>
      </div>
      <div className="toolbar">
        <button className="secondary-button" type="button" onClick={onBack}>
          Back to job details
        </button>
        <button
          className="secondary-button"
          type="button"
          disabled={refreshingAnalysis || busy}
          onClick={() => void refreshAnalysis()}
        >
          {refreshingAnalysis ? 'Refreshing...' : 'Refresh analysis'}
        </button>
      </div>
      <p className="status-line" role="status" aria-live="polite">
        {message || (refreshingAnalysis ? 'Updating analysis from the server...' : '')}
      </p>
      {failure && (
        <div className="alert" role="alert">
          <p>{failure}</p>
          {conflict && <button onClick={() => void loadAll()}>Reload latest</button>}
        </div>
      )}
      <SnapshotContext job={job} snapshot={snapshot} />
      <div className="fit-grid">
        <section aria-labelledby="requirements-title">
          <div className="fact-title">
            <div>
              <h2 id="requirements-title">Requirements</h2>
              <p className="inline-note">
                Draft by default. Confirm deliberately before a requirement affects analysis.
              </p>
            </div>
            <button type="button" onClick={() => setCreateOpen((open) => !open)}>
              Add requirement
            </button>
          </div>
          {(createOpen || editingRequirementId) && (
            <RequirementEditor
              form={requirementForm}
              errors={requirementErrors}
              busy={busy}
              title={editingRequirementId ? 'Edit requirement' : 'Add draft requirement'}
              onChange={setRequirementForm}
              onSubmit={saveRequirement}
              onCancel={() => {
                setCreateOpen(false);
                setEditingRequirementId(undefined);
                setRequirementForm(blankRequirement);
                setRequirementErrors({});
              }}
            />
          )}
          <RequirementList
            requirements={requirements}
            linksByRequirement={linksByRequirement}
            busy={busy}
            evidenceRequirementId={evidenceRequirementId}
            editingLinkId={editingLinkId}
            evidenceForm={evidenceForm}
            evidenceCandidates={evidenceCandidates}
            onEditRequirement={editRequirement}
            onStatus={changeRequirementStatus}
            onDeleteRequirement={deleteRequirement}
            onStartEvidence={startEvidenceLink}
            onEditLink={editEvidenceLink}
            onRemoveLink={removeEvidenceLink}
            onEvidenceForm={setEvidenceForm}
            onSaveEvidence={saveEvidenceLink}
            onCancelEvidence={() => {
              setEvidenceRequirementId(undefined);
              setEditingLinkId(undefined);
            }}
          />
        </section>
        <section aria-labelledby="analysis-title">
          <h2 id="analysis-title">Server analysis</h2>
          {analysis ? (
            <>
              <FitScoreSummary analysis={analysis} />
              <FitImportanceBreakdown breakdowns={analysis.importanceBreakdowns} />
              <AssessmentFiltersView
                filters={filters}
                onChange={setFilters}
                count={filteredAssessments.length}
              />
              <RequirementAssessmentList assessments={filteredAssessments} />
              <FitFindings analysis={analysis} />
            </>
          ) : (
            <p className="empty-note">Analysis has not loaded yet.</p>
          )}
        </section>
      </div>
    </section>
  );
}

function SnapshotContext({
  job,
  snapshot,
}: {
  job: CapturedJob;
  snapshot: JobDescriptionSnapshot;
}) {
  return (
    <section className="detail-panel snapshot-context" aria-labelledby="snapshot-context-title">
      <h2 id="snapshot-context-title">Exact immutable snapshot</h2>
      <dl>
        <Detail name="Company" value={job.companyName} />
        <Detail name="Job title" value={job.jobTitle} />
        <Detail
          name="Snapshot"
          value={`Snapshot ${snapshot.sequence} captured ${formatDate(snapshot.capturedAt)}`}
        />
        <Detail name="Source" value={label(snapshot.sourceType)} />
      </dl>
      <p className="inline-note">
        Requirements are interpretations of this exact snapshot. Adding a newer snapshot does not
        update requirements attached here. The application does not fetch or re-check posting URLs,
        and job text is not converted into candidate facts.
      </p>
      <details>
        <summary>Read snapshot description</summary>
        <pre>{snapshot.descriptionText}</pre>
      </details>
    </section>
  );
}

function RequirementEditor({
  title,
  form,
  errors,
  busy,
  onChange,
  onSubmit,
  onCancel,
}: {
  title: string;
  form: RequirementForm;
  errors: Record<string, string>;
  busy: boolean;
  onChange: (form: RequirementForm) => void;
  onSubmit: (event: FormEvent) => void;
  onCancel: () => void;
}) {
  return (
    <form
      aria-label={title}
      className="profile-form compact-form"
      onSubmit={(event) => void onSubmit(event)}
      noValidate
    >
      <fieldset disabled={busy}>
        <legend>{title}</legend>
        <label>
          Category
          <select
            value={form.category}
            onChange={(event) =>
              onChange({ ...form, category: event.target.value as RequirementCategory })
            }
          >
            {requirementCategories.map((category) => (
              <option key={category} value={category}>
                {label(category)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Importance
          <select
            value={form.importance}
            onChange={(event) =>
              onChange({ ...form, importance: event.target.value as RequirementImportance })
            }
          >
            {requirementImportances.map((importance) => (
              <option key={importance} value={importance}>
                {label(importance)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Review status
          <select
            value={form.status}
            onChange={(event) =>
              onChange({ ...form, status: event.target.value as RequirementStatus })
            }
          >
            {requirementStatuses.map((status) => (
              <option key={status} value={status}>
                {label(status)}
              </option>
            ))}
          </select>
        </label>
        <TextArea
          idPrefix="fit-requirement"
          label="Requirement text"
          value={form.requirementText}
          required
          maxLength={requirementLimits.requirementText}
          error={errors.requirementText}
          onChange={(value) => onChange({ ...form, requirementText: value })}
        />
        <TextArea
          idPrefix="fit-requirement"
          label="Source excerpt"
          value={form.sourceExcerpt}
          maxLength={requirementLimits.sourceExcerpt}
          helper="Optional. Paste only the relevant job-description excerpt."
          error={errors.sourceExcerpt}
          onChange={(value) => onChange({ ...form, sourceExcerpt: value })}
        />
      </fieldset>
      <div className="form-actions">
        <button disabled={busy}>{busy ? 'Saving...' : 'Save requirement'}</button>
        <button type="button" className="secondary-button" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function RequirementList(props: {
  requirements: JobRequirement[];
  linksByRequirement: Record<string, CandidateEvidenceLink[]>;
  busy: boolean;
  evidenceRequirementId: string | undefined;
  editingLinkId: string | undefined;
  evidenceForm: EvidenceForm;
  evidenceCandidates: EvidenceCandidate[];
  onEditRequirement: (requirement: JobRequirement) => void;
  onStatus: (requirement: JobRequirement, status: RequirementStatus) => void;
  onDeleteRequirement: (requirement: JobRequirement) => void;
  onStartEvidence: (requirement: JobRequirement) => void;
  onEditLink: (link: CandidateEvidenceLink) => void;
  onRemoveLink: (link: CandidateEvidenceLink) => void;
  onEvidenceForm: (form: EvidenceForm) => void;
  onSaveEvidence: (event: FormEvent) => void;
  onCancelEvidence: () => void;
}) {
  return (
    <div className="requirement-groups">
      {requirementStatuses.map((status) => {
        const group = props.requirements.filter((requirement) => requirement.status === status);
        return (
          <section key={status} aria-labelledby={`requirements-${status.toLowerCase()}`}>
            <h3 id={`requirements-${status.toLowerCase()}`}>{label(status)}</h3>
            {group.length === 0 ? (
              <p className="empty-note">No {label(status).toLowerCase()} requirements.</p>
            ) : (
              <ul className="fact-list">
                {group.map((requirement) => {
                  const links = props.linksByRequirement[requirement.id] ?? [];
                  return (
                    <li className="fact-card" key={requirement.id}>
                      <div className="fact-title">
                        <div>
                          <h4>{requirement.requirementText}</h4>
                          <p className="inline-note">
                            {label(requirement.category)} - {label(requirement.importance)} -{' '}
                            {links.length} evidence links
                          </p>
                        </div>
                        <span className={`status-pill status-${requirement.status.toLowerCase()}`}>
                          {label(requirement.status)}
                        </span>
                      </div>
                      {requirement.sourceExcerpt && (
                        <blockquote>{requirement.sourceExcerpt}</blockquote>
                      )}
                      <div className="fact-actions">
                        <button
                          type="button"
                          disabled={props.busy}
                          onClick={() => props.onEditRequirement(requirement)}
                        >
                          Edit
                        </button>
                        {requirement.status !== 'CONFIRMED' && (
                          <button
                            type="button"
                            disabled={props.busy}
                            onClick={() => void props.onStatus(requirement, 'CONFIRMED')}
                          >
                            Confirm
                          </button>
                        )}
                        {requirement.status !== 'REJECTED' && (
                          <button
                            type="button"
                            disabled={props.busy}
                            onClick={() => void props.onStatus(requirement, 'REJECTED')}
                          >
                            Reject
                          </button>
                        )}
                        {requirement.status !== 'DRAFT' && (
                          <button
                            type="button"
                            className="secondary-button"
                            disabled={props.busy}
                            onClick={() => void props.onStatus(requirement, 'DRAFT')}
                          >
                            Return to draft
                          </button>
                        )}
                        <button
                          type="button"
                          className="secondary-button"
                          disabled={props.busy}
                          onClick={() => void props.onDeleteRequirement(requirement)}
                        >
                          Delete
                        </button>
                      </div>
                      {requirement.status === 'CONFIRMED' ? (
                        <>
                          <EvidenceLinks
                            links={links}
                            candidates={props.evidenceCandidates}
                            onEdit={props.onEditLink}
                            onRemove={props.onRemoveLink}
                            busy={props.busy}
                          />
                          {props.evidenceRequirementId === requirement.id ? (
                            <EvidenceLinkEditor {...props} />
                          ) : (
                            <button
                              type="button"
                              className="secondary-button"
                              disabled={props.busy}
                              onClick={() => props.onStartEvidence(requirement)}
                            >
                              Link evidence
                            </button>
                          )}
                        </>
                      ) : (
                        <p className="inline-note">
                          Confirm this requirement before linking evidence.
                        </p>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        );
      })}
    </div>
  );
}

function EvidenceLinks({
  links,
  candidates,
  busy,
  onEdit,
  onRemove,
}: {
  links: CandidateEvidenceLink[];
  candidates: EvidenceCandidate[];
  busy: boolean;
  onEdit: (link: CandidateEvidenceLink) => void;
  onRemove: (link: CandidateEvidenceLink) => void;
}) {
  if (links.length === 0) return <p className="empty-note">No linked evidence yet.</p>;
  return (
    <ul className="evidence-list" aria-label="Linked evidence">
      {links.map((link) => (
        <li key={link.id}>
          <strong>{evidenceLabel(candidates, link)}</strong>
          <span>
            {label(link.relationship)} - {label(link.evidenceType)}
          </span>
          {link.userNote && <p>{link.userNote}</p>}
          <div className="fact-actions">
            <button type="button" disabled={busy} onClick={() => onEdit(link)}>
              Edit link
            </button>
            <button
              type="button"
              className="secondary-button"
              disabled={busy}
              onClick={() => onRemove(link)}
            >
              Remove link
            </button>
          </div>
        </li>
      ))}
    </ul>
  );
}

function EvidenceLinkEditor(props: {
  evidenceForm: EvidenceForm;
  evidenceCandidates: EvidenceCandidate[];
  busy: boolean;
  editingLinkId: string | undefined;
  onEvidenceForm: (form: EvidenceForm) => void;
  onSaveEvidence: (event: FormEvent) => void;
  onCancelEvidence: () => void;
}) {
  const candidates = props.evidenceCandidates.filter((candidate) =>
    props.evidenceForm.evidenceType ? candidate.type === props.evidenceForm.evidenceType : true,
  );
  return (
    <form
      aria-label={props.editingLinkId ? 'Edit evidence relationship' : 'Link eligible evidence'}
      className="profile-form compact-form"
      onSubmit={(event) => void props.onSaveEvidence(event)}
    >
      <fieldset disabled={props.busy}>
        <legend>
          {props.editingLinkId ? 'Edit evidence relationship' : 'Link eligible evidence'}
        </legend>
        <p className="inline-note">
          Not demonstrated means the currently linked evidence does not show this requirement. It
          does not mean you do not possess it.
        </p>
        <label>
          Evidence source
          <select
            required
            value={props.evidenceForm.evidenceType}
            onChange={(event) =>
              props.onEvidenceForm({
                ...props.evidenceForm,
                evidenceType: event.target.value as EvidenceType | '',
                evidenceId: '',
              })
            }
          >
            <option value="">Choose a source</option>
            <option value="CAREER_FACT">Confirmed career facts</option>
            <option value="PROFILE_FIELD">Supported profile fields</option>
            <option value="RESUME_VERSION">Base resume version</option>
          </select>
        </label>
        <label>
          Evidence
          <select
            required
            value={props.evidenceForm.evidenceId}
            onChange={(event) => {
              const selected = props.evidenceCandidates.find(
                (candidate) => candidate.id === event.target.value,
              );
              props.onEvidenceForm({
                ...props.evidenceForm,
                evidenceId: event.target.value,
                evidenceType: selected?.type ?? props.evidenceForm.evidenceType,
              });
            }}
          >
            <option value="">Choose evidence</option>
            {candidates.map((candidate) => (
              <option key={`${candidate.type}-${candidate.id}`} value={candidate.id}>
                {candidate.group}: {candidate.label}
              </option>
            ))}
          </select>
        </label>
        {candidates.length === 0 ? (
          <p className="empty-note">No eligible evidence is available for the selected source.</p>
        ) : (
          <ul className="evidence-candidate-list" aria-label="Eligible evidence candidates">
            {candidates.map((candidate) => (
              <li key={`${candidate.type}-${candidate.id}`}>
                <strong>{candidate.label}</strong>
                <span>{candidate.detail}</span>
              </li>
            ))}
          </ul>
        )}
        <fieldset className="relationship-fieldset">
          <legend>Evidence relationship</legend>
          {evidenceRelationships.map((relationship) => (
            <label className="checkbox-label" key={relationship}>
              <input
                type="radio"
                name="evidence-relationship"
                value={relationship}
                checked={props.evidenceForm.relationship === relationship}
                onChange={() => props.onEvidenceForm({ ...props.evidenceForm, relationship })}
              />
              <span>{relationshipExplanation(relationship)}</span>
            </label>
          ))}
        </fieldset>
        <TextArea
          idPrefix="evidence-link"
          label="User note"
          value={props.evidenceForm.userNote}
          maxLength={1000}
          helper="Optional note about why you selected this relationship."
          onChange={(value) => props.onEvidenceForm({ ...props.evidenceForm, userNote: value })}
        />
      </fieldset>
      <div className="form-actions">
        <button disabled={props.busy}>{props.busy ? 'Saving...' : 'Save evidence link'}</button>
        <button
          type="button"
          className="secondary-button"
          disabled={props.busy}
          onClick={props.onCancelEvidence}
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

function FitScoreSummary({ analysis }: { analysis: FitAnalysis }) {
  if (analysis.analysisStatus !== 'SCORABLE') {
    return (
      <div className="fit-summary">
        <p>Confirm at least one reviewed requirement to calculate evidence support and coverage.</p>
        <p className="inline-note">
          Draft: {analysis.draftRequirementCount}. Rejected: {analysis.rejectedRequirementCount}.
        </p>
      </div>
    );
  }
  return (
    <div className="fit-summary">
      <ScoreMeter
        label="Evidence support"
        value={analysis.evidenceSupportScore ?? 0}
        description="How much of the confirmed requirement weight is demonstrated by your linked evidence."
      />
      <ScoreMeter
        label="Review coverage"
        value={analysis.evidenceCoverageScore ?? 0}
        description="How much of the confirmed requirement weight has been explicitly reviewed with at least one evidence link."
      />
      <p className="inline-note">
        Draft and rejected requirements are excluded. Multiple links do not increase a requirement's
        scoring weight. Contradictions are reported explicitly and do not create negative points.
      </p>
    </div>
  );
}

function ScoreMeter({
  label: title,
  value,
  description,
}: {
  label: string;
  value: number;
  description: string;
}) {
  return (
    <div className="score-meter">
      <strong>
        {title}: {value}%
      </strong>
      <progress aria-label={title} max={100} value={value} />
      <p>{description}</p>
    </div>
  );
}

function FitImportanceBreakdown({ breakdowns }: { breakdowns: ImportanceBreakdown[] }) {
  return (
    <div className="table-wrap">
      <table>
        <caption>Importance breakdowns</caption>
        <thead>
          <tr>
            <th>Importance</th>
            <th>Confirmed</th>
            <th>Assessed</th>
            <th>Demonstrated</th>
            <th>Partial</th>
            <th>Not demonstrated</th>
            <th>Contradicted</th>
            <th>Conflicting</th>
            <th>Unassessed</th>
            <th>Evidence support</th>
            <th>Review coverage</th>
          </tr>
        </thead>
        <tbody>
          {breakdowns.map((breakdown) => (
            <tr key={breakdown.importance}>
              <th>{label(breakdown.importance)}</th>
              {breakdown.applicable ? (
                <>
                  <td>{breakdown.confirmedRequirementCount}</td>
                  <td>{breakdown.assessedCount}</td>
                  <td>{breakdown.demonstratedCount}</td>
                  <td>{breakdown.partiallyDemonstratedCount}</td>
                  <td>{breakdown.notDemonstratedCount}</td>
                  <td>{breakdown.contradictedCount}</td>
                  <td>{breakdown.conflictingEvidenceCount}</td>
                  <td>{breakdown.unassessedCount}</td>
                  <td>{breakdown.evidenceSupportScore}%</td>
                  <td>{breakdown.evidenceCoverageScore}%</td>
                </>
              ) : (
                <td colSpan={10}>No confirmed requirements</td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AssessmentFiltersView({
  filters,
  count,
  onChange,
}: {
  filters: AssessmentFilters;
  count: number;
  onChange: (filters: AssessmentFilters) => void;
}) {
  const active = filters.importance || filters.assessment || filters.category;
  return (
    <div className="filter-bar" aria-label="Requirement assessment filters">
      <label>
        Importance
        <select
          value={filters.importance}
          onChange={(event) =>
            onChange({ ...filters, importance: event.target.value as RequirementImportance | '' })
          }
        >
          <option value="">All importance</option>
          {requirementImportances.map((importance) => (
            <option key={importance} value={importance}>
              {label(importance)}
            </option>
          ))}
        </select>
      </label>
      <label>
        Assessment
        <select
          value={filters.assessment}
          onChange={(event) =>
            onChange({ ...filters, assessment: event.target.value as RequirementAssessment | '' })
          }
        >
          <option value="">All assessments</option>
          {assessmentOptions.map((assessment) => (
            <option key={assessment} value={assessment}>
              {assessmentLabel(assessment)}
            </option>
          ))}
        </select>
      </label>
      <label>
        Category
        <select
          value={filters.category}
          onChange={(event) =>
            onChange({ ...filters, category: event.target.value as RequirementCategory | '' })
          }
        >
          <option value="">All categories</option>
          {requirementCategories.map((category) => (
            <option key={category} value={category}>
              {label(category)}
            </option>
          ))}
        </select>
      </label>
      <button
        className="secondary-button"
        type="button"
        disabled={!active}
        onClick={() => onChange({ importance: '', assessment: '', category: '' })}
      >
        Clear filters
      </button>
      <p className="inline-note" role="status" aria-live="polite">
        {count} confirmed requirement explanations shown.
      </p>
    </div>
  );
}

const assessmentOptions: RequirementAssessment[] = [
  'DEMONSTRATED',
  'PARTIALLY_DEMONSTRATED',
  'NOT_DEMONSTRATED',
  'CONTRADICTED',
  'CONFLICTING_EVIDENCE',
  'UNASSESSED',
];

function RequirementAssessmentList({
  assessments,
}: {
  assessments: RequirementAssessmentResponse[];
}) {
  if (assessments.length === 0)
    return <p className="empty-note">No confirmed requirement explanations match these filters.</p>;
  return (
    <ol className="assessment-list">
      {assessments.map((assessment) => (
        <li className="fact-card" key={assessment.requirementId}>
          <h3>{assessment.requirementText}</h3>
          <p>
            <strong>{assessmentLabel(assessment.assessment)}</strong> -{' '}
            {reasonLabel(assessment.assessment)}
          </p>
          <dl>
            <Detail name="Category" value={label(assessment.requirementCategory)} />
            <Detail name="Importance" value={label(assessment.importance)} />
            <Detail name="Weight" value={assessment.requirementWeight} />
            <Detail name="Evidence credit" value={String(assessment.evidenceCredit)} />
            <Detail name="Contribution" value={String(assessment.weightedContribution)} />
            <Detail name="Relationships" value={relationshipCounts(assessment)} />
          </dl>
          {assessment.sourceExcerpt && <blockquote>{assessment.sourceExcerpt}</blockquote>}
          {assessment.evidenceLinks.length > 0 ? (
            <ul className="evidence-list">
              {assessment.evidenceLinks.map((link) => (
                <li key={link.evidenceLinkId}>
                  <strong>{label(link.evidenceType)}</strong>
                  <span>
                    {label(link.relationship)} - {link.evidenceReference}
                  </span>
                  {link.userNote && <p>{link.userNote}</p>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="empty-note">No evidence has been linked yet.</p>
          )}
        </li>
      ))}
    </ol>
  );
}

function FitFindings({ analysis }: { analysis: FitAnalysis }) {
  const partial = analysis.gaps.filter((finding) => finding.findingType === 'PARTIAL_EVIDENCE');
  const gaps = analysis.gaps.filter((finding) => finding.findingType !== 'PARTIAL_EVIDENCE');
  return (
    <div className="findings-grid">
      <FindingSection
        title="Evidence gaps"
        findings={gaps}
        empty="No evidence gaps in the current server result."
      />
      <FindingSection
        title="Partial evidence"
        findings={partial}
        empty="No partially demonstrated requirements in the current server result."
      />
      <FindingSection
        title="Contradictions and conflicts"
        findings={analysis.contradictions}
        empty="No contradictions or conflicts in the current server result."
      />
    </div>
  );
}

function FindingSection({
  title,
  findings,
  empty,
}: {
  title: string;
  findings: FitFinding[];
  empty: string;
}) {
  return (
    <section aria-labelledby={fieldId(title)}>
      <h3 id={fieldId(title)}>{title}</h3>
      {findings.length === 0 ? (
        <p className="empty-note">{empty}</p>
      ) : (
        <ul className="record-list">
          {[...findings].sort(findingOrder).map((finding) => (
            <li className="finding-item" key={`${title}-${finding.requirementId}`}>
              <strong>{label(finding.importance)}</strong>
              <span>{findingText(finding.findingType)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function TextArea({
  idPrefix,
  label: title,
  value,
  onChange,
  maxLength,
  required,
  helper,
  error,
}: {
  idPrefix: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  maxLength?: number | undefined;
  required?: boolean | undefined;
  helper?: string | undefined;
  error?: string | undefined;
}) {
  const id = `${idPrefix}-${fieldId(title)}`;
  return (
    <label htmlFor={id}>
      {title}
      <textarea
        id={id}
        required={required}
        maxLength={maxLength}
        value={value}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={`${id}-help ${id}-error`}
        onChange={(event) => onChange(event.target.value)}
      />
      <span className="helper" id={`${id}-help`}>
        {helper}
        {maxLength ? ` ${value.length}/${maxLength}` : ''}
      </span>
      <span className="field-error" id={`${id}-error`}>
        {error}
      </span>
    </label>
  );
}

function Detail({ name, value }: { name: string; value: ReactNode }) {
  return (
    <div>
      <dt>{name}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function buildEvidenceCandidates(
  profile: CandidateProfile | undefined,
  facts: CareerFact[],
  resume: BaseResumeMetadata | undefined,
) {
  const candidates: EvidenceCandidate[] = facts.map((fact) => ({
    type: 'CAREER_FACT',
    id: fact.id,
    group: 'Confirmed career facts',
    label: `${label(fact.category)}: ${fact.factualContent}`,
    detail:
      [fact.organization, fact.title, fact.location].filter(Boolean).join(' - ') ||
      'Confirmed fact',
  }));
  if (profile) {
    for (const field of profileEvidenceFields) {
      const value = profile[field.field];
      if (typeof value === 'string' && value.trim()) {
        candidates.push({
          type: 'PROFILE_FIELD',
          id: field.id,
          group: 'Supported profile fields',
          label: field.label,
          detail: value,
        });
      }
    }
  }
  if (resume) {
    candidates.push({
      type: 'RESUME_VERSION',
      id: resume.id,
      group: 'Base resume version',
      label: resume.originalFilename,
      detail: `${resume.mediaType}, ${formatBytes(resume.byteSize)}, updated ${formatDate(resume.updatedAt)}`,
    });
  }
  return candidates;
}

function validateRequirement(form: RequirementForm) {
  const errors: Record<string, string> = {};
  if (!form.requirementText.trim()) errors.requirementText = 'Requirement text is required.';
  if (form.requirementText.length > requirementLimits.requirementText)
    errors.requirementText = 'Use 1000 characters or fewer.';
  if (form.sourceExcerpt.length > requirementLimits.sourceExcerpt)
    errors.sourceExcerpt = 'Use 4000 characters or fewer.';
  return errors;
}

function cleanRequirement(form: RequirementForm): RequirementFields {
  return {
    category: form.category,
    importance: form.importance,
    requirementText: form.requirementText.trim(),
    sourceExcerpt: optional(form.sourceExcerpt),
    status: form.status,
  };
}

function requirementToForm(requirement: JobRequirement): RequirementForm {
  return {
    category: requirement.category,
    importance: requirement.importance,
    requirementText: requirement.requirementText,
    sourceExcerpt: requirement.sourceExcerpt ?? '',
    status: requirement.status,
  };
}

function handleWriteFailure(
  error: unknown,
  onExpired: () => void,
  setFailure: (message: string) => void,
  setConflict: (conflict: boolean) => void,
  fallback: string,
) {
  if (error instanceof ApiError && error.status === 401) onExpired();
  else if (error instanceof ApiError && error.status === 409) {
    setConflict(true);
    setFailure(
      error.code === 'stale_version'
        ? 'This record changed elsewhere. Review your unsaved values, then reload the latest version.'
        : fallback,
    );
  } else setFailure(readableError(error, fallback));
}

function readableError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    if (error.status === 400)
      return 'Some fit review information is invalid. Review the form and try again.';
    if (error.status === 403)
      return 'The request was not accepted. Refresh the page before trying another saved change.';
    if (error.status === 404)
      return 'That private job, snapshot, requirement, or evidence item was not found.';
  }
  return fallback;
}

function relationshipExplanation(relationship: EvidenceRelationship) {
  const copy: Record<EvidenceRelationship, string> = {
    SUPPORTS: 'Supports: The selected evidence clearly demonstrates this requirement.',
    PARTIALLY_SUPPORTS:
      'Partially supports: The evidence demonstrates part, but not all, of the requirement.',
    CONTRADICTS: 'Contradicts: The evidence conflicts with the requirement.',
    NOT_DEMONSTRATED: 'Not demonstrated: This evidence does not demonstrate the requirement.',
  };
  return copy[relationship];
}

function assessmentLabel(assessment: RequirementAssessment) {
  const labels: Record<RequirementAssessment, string> = {
    DEMONSTRATED: 'Demonstrated by linked evidence',
    PARTIALLY_DEMONSTRATED: 'Partially demonstrated',
    NOT_DEMONSTRATED: 'Not demonstrated by linked evidence',
    CONTRADICTED: 'Contradicting evidence linked',
    CONFLICTING_EVIDENCE: 'Supporting and contradicting evidence linked',
    UNASSESSED: 'Not reviewed against evidence',
  };
  return labels[assessment];
}

function reasonLabel(assessment: RequirementAssessment) {
  const labels: Record<RequirementAssessment, string> = {
    DEMONSTRATED: 'Current linked evidence demonstrates this requirement.',
    PARTIALLY_DEMONSTRATED: 'Current evidence partially demonstrates this requirement.',
    NOT_DEMONSTRATED: 'Current linked evidence does not demonstrate this requirement.',
    CONTRADICTED: 'Linked evidence contradicts this requirement.',
    CONFLICTING_EVIDENCE: 'Both supporting and contradicting evidence are linked.',
    UNASSESSED: 'No evidence has been linked yet.',
  };
  return labels[assessment];
}

function findingText(type: FitFinding['findingType']) {
  const labels: Record<FitFinding['findingType'], string> = {
    UNASSESSED_REQUIREMENT: 'No evidence has been linked yet.',
    EVIDENCE_NOT_DEMONSTRATED: 'Current linked evidence does not demonstrate this requirement.',
    PARTIAL_EVIDENCE: 'Current evidence partially demonstrates this requirement.',
    CONTRADICTING_EVIDENCE: 'Linked evidence contradicts this requirement.',
    CONFLICTING_EVIDENCE: 'Both supporting and contradicting evidence are linked.',
  };
  return labels[type];
}

function findingOrder(a: FitFinding, b: FitFinding) {
  return importanceRank(a.importance) - importanceRank(b.importance);
}

function importanceRank(importance: RequirementImportance) {
  return importance === 'REQUIRED' ? 0 : importance === 'PREFERRED' ? 1 : 2;
}

function relationshipCounts(assessment: RequirementAssessmentResponse) {
  const counts = assessment.evidenceRelationshipCounts;
  return `${counts.supportsCount} supports, ${counts.partiallySupportsCount} partial, ${counts.contradictsCount} contradicts, ${counts.notDemonstratedCount} not demonstrated`;
}

function evidenceLabel(candidates: EvidenceCandidate[], link: CandidateEvidenceLink) {
  return (
    candidates.find(
      (candidate) => candidate.type === link.evidenceType && candidate.id === link.evidenceId,
    )?.label ?? label(link.evidenceType)
  );
}

function optional(value: string) {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function label(value: string) {
  return value
    .toLowerCase()
    .split('_')
    .map((part) => (part[0] ?? '').toUpperCase() + part.slice(1))
    .join(' ');
}

function formatDate(value: string) {
  return new Date(value).toLocaleString();
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

function fieldId(labelText: string) {
  return labelText
    .toLowerCase()
    .replaceAll(/[^a-z0-9]+/g, '-')
    .replaceAll(/^-|-$/g, '');
}
