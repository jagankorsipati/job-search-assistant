import { type FormEvent, type ReactNode, useCallback, useEffect, useState } from 'react';
import { ApiError } from '../../api/client';
import {
  employmentTypes,
  jobsApi,
  jobSourceTypes,
  type CaptureJobRequest,
  type CapturedJob,
  type EmploymentType,
  type JobDescriptionSnapshot,
  type JobSourceType,
} from '../../api/jobs';

const blankJobForm: JobForm = {
  companyName: '',
  jobTitle: '',
  workLocation: '',
  postingUrl: '',
  sourceType: 'MANUAL',
  employmentType: '',
  externalPostingId: '',
  datePosted: '',
  descriptionText: '',
};

interface JobForm {
  companyName: string;
  jobTitle: string;
  workLocation: string;
  postingUrl: string;
  sourceType: JobSourceType;
  employmentType: EmploymentType | '';
  externalPostingId: string;
  datePosted: string;
  descriptionText: string;
}

export function JobsWorkspace({ onExpired }: { onExpired: () => void }) {
  const [archived, setArchived] = useState(false);
  const [jobs, setJobs] = useState<CapturedJob[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [selected, setSelected] = useState<CapturedJob>();
  const [snapshots, setSnapshots] = useState<JobDescriptionSnapshot[]>([]);
  const [mode, setMode] = useState<'loading' | 'ready'>('loading');
  const [message, setMessage] = useState('');
  const [failure, setFailure] = useState('');
  const [conflict, setConflict] = useState(false);
  const [busy, setBusy] = useState(false);
  const [captureOpen, setCaptureOpen] = useState(false);
  const [captureForm, setCaptureForm] = useState<JobForm>(blankJobForm);
  const [editForm, setEditForm] = useState<JobForm>(blankJobForm);
  const [editing, setEditing] = useState(false);
  const [snapshotForm, setSnapshotForm] = useState({
    sourceType: 'PASTED_DESCRIPTION' as JobSourceType,
    descriptionText: '',
  });
  const [errors, setErrors] = useState<Record<string, string>>({});

  const loadJobs = useCallback(async () => {
    setMode('loading');
    setFailure('');
    try {
      const loaded = await jobsApi.listJobs({ archived, limit: 100 });
      setJobs(loaded);
      setSelectedId((current) =>
        current && loaded.some((job) => job.id === current) ? current : loaded[0]?.id,
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onExpired();
      else setFailure(readableError(error, 'Jobs are temporarily unavailable.'));
    } finally {
      setMode('ready');
    }
  }, [archived, onExpired]);

  const loadSelected = useCallback(async () => {
    if (!selectedId) {
      setSelected(undefined);
      setSnapshots([]);
      return;
    }
    setFailure('');
    try {
      const [job, history] = await Promise.all([
        jobsApi.getJob(selectedId),
        jobsApi.listSnapshots(selectedId, 50),
      ]);
      setSelected(job);
      setEditForm(jobToForm(job));
      setSnapshots(history);
      setEditing(false);
      setConflict(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onExpired();
      else setFailure(readableError(error, 'The selected job could not be loaded.'));
    }
  }, [onExpired, selectedId]);

  useEffect(() => {
    const load = async () => {
      await loadJobs();
    };
    void load();
  }, [loadJobs]);

  useEffect(() => {
    const load = async () => {
      await loadSelected();
    };
    void load();
  }, [loadSelected]);

  const capture = async (event: FormEvent) => {
    event.preventDefault();
    const validation = validateJob(captureForm, true);
    setErrors(validation);
    if (Object.keys(validation).length > 0) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const created = await jobsApi.captureJob(cleanJob(captureForm));
      setCaptureOpen(false);
      setCaptureForm(blankJobForm);
      setJobs((current) => [created.job, ...current]);
      setSelectedId(created.job.id);
      setSelected(created.job);
      setSnapshots(created.initialSnapshot ? [created.initialSnapshot] : []);
      setMessage(created.initialSnapshot ? 'Job captured with initial snapshot.' : 'Job captured.');
    } catch (error) {
      handleWriteError(error, onExpired, setFailure, setConflict, 'The job was not captured.');
    } finally {
      setBusy(false);
    }
  };

  const saveEdit = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected) return;
    const validation = validateJob(editForm, false);
    setErrors(validation);
    if (Object.keys(validation).length > 0) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved = await jobsApi.updateJob(selected.id, {
        ...cleanJob(editForm),
        expectedVersion: selected.version,
      });
      setSelected(saved);
      setJobs((current) => current.map((job) => (job.id === saved.id ? saved : job)));
      setEditing(false);
      setConflict(false);
      setMessage('Job metadata saved.');
    } catch (error) {
      handleWriteError(error, onExpired, setFailure, setConflict, 'The job was not saved.');
    } finally {
      setBusy(false);
    }
  };

  const archiveState = async (action: 'archive' | 'restore') => {
    if (!selected) return;
    const verb = action === 'archive' ? 'Archive' : 'Restore';
    if (!window.confirm(`${verb} this job?`)) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved =
        action === 'archive'
          ? await jobsApi.archiveJob(selected.id, selected.version)
          : await jobsApi.restoreJob(selected.id, selected.version);
      setSelected(saved);
      setJobs((current) => current.filter((job) => job.id !== saved.id));
      setMessage(action === 'archive' ? 'Job archived.' : 'Job restored.');
      await loadJobs();
    } catch (error) {
      handleWriteError(
        error,
        onExpired,
        setFailure,
        setConflict,
        'The archive action was not completed.',
      );
    } finally {
      setBusy(false);
    }
  };

  const appendSnapshot = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected) return;
    if (!snapshotForm.descriptionText.trim()) {
      setErrors({ descriptionText: 'Description text is required.' });
      return;
    }
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const snapshot = await jobsApi.appendSnapshot(selected.id, {
        sourceType: snapshotForm.sourceType,
        descriptionText: snapshotForm.descriptionText.trim(),
      });
      setSnapshots((current) => [...current, snapshot].sort((a, b) => a.sequence - b.sequence));
      setSnapshotForm({ sourceType: 'PASTED_DESCRIPTION', descriptionText: '' });
      setConflict(false);
      setMessage('Immutable snapshot appended.');
    } catch (error) {
      handleWriteError(
        error,
        onExpired,
        setFailure,
        setConflict,
        error instanceof ApiError && error.code === 'duplicate_snapshot'
          ? 'That description matches the latest snapshot.'
          : 'The snapshot was not appended.',
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="workspace-section" aria-labelledby="jobs-title">
      <div className="section-heading">
        <p className="eyebrow">Job workspace</p>
        <h1 id="jobs-title">Jobs</h1>
        <p>
          Capture opportunities without implying that you applied. URL references are stored only.
        </p>
      </div>
      <p className="status-line" role="status" aria-live="polite">
        {message}
      </p>
      {failure && (
        <div className="alert" role="alert">
          <p>{failure}</p>
          {conflict && <button onClick={() => void loadSelected()}>Reload latest job</button>}
        </div>
      )}
      <div className="workspace-grid">
        <div>
          <div className="toolbar">
            <button onClick={() => setCaptureOpen((open) => !open)}>Capture job</button>
            <button
              className="secondary-button"
              onClick={() => setArchived(false)}
              aria-pressed={!archived}
            >
              Active jobs
            </button>
            <button
              className="secondary-button"
              onClick={() => setArchived(true)}
              aria-pressed={archived}
            >
              Archived jobs
            </button>
          </div>
          {captureOpen && (
            <JobFormView
              idPrefix="capture"
              title="Capture job"
              form={captureForm}
              errors={errors}
              busy={busy}
              includeDescription
              onChange={setCaptureForm}
              onSubmit={capture}
              onCancel={() => setCaptureOpen(false)}
            />
          )}
          {mode === 'loading' ? (
            <p>Loading jobs...</p>
          ) : jobs.length === 0 ? (
            <p className="empty-note">{archived ? 'No archived jobs.' : 'No active jobs yet.'}</p>
          ) : (
            <ul className="record-list" aria-label={archived ? 'Archived jobs' : 'Active jobs'}>
              {jobs.map((job) => (
                <li key={job.id}>
                  <button
                    className="record-button"
                    aria-current={selectedId === job.id ? 'true' : undefined}
                    onClick={() => setSelectedId(job.id)}
                  >
                    <strong>{job.companyName}</strong>
                    <span>{job.jobTitle}</span>
                    <span>
                      {job.workLocation || 'Location not provided'} - {label(job.sourceType)}
                    </span>
                    <span>
                      {job.archived ? 'Archived' : `Updated ${formatDate(job.metadataUpdatedAt)}`}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div>
          {selected ? (
            <article className="detail-panel">
              <div className="fact-title">
                <div>
                  <h2>{selected.companyName}</h2>
                  <p>{selected.jobTitle}</p>
                </div>
                {selected.archived ? (
                  <button disabled={busy} onClick={() => void archiveState('restore')}>
                    Restore
                  </button>
                ) : (
                  <button disabled={busy} onClick={() => void archiveState('archive')}>
                    Archive
                  </button>
                )}
              </div>
              <dl>
                <Detail name="Location" value={selected.workLocation || 'Not provided'} />
                <Detail name="Source" value={label(selected.sourceType)} />
                <Detail
                  name="Employment"
                  value={selected.employmentType ? label(selected.employmentType) : 'Not provided'}
                />
                <Detail name="Date posted" value={selected.datePosted || 'Not provided'} />
                <Detail name="Captured" value={formatDate(selected.capturedAt)} />
                <Detail name="Updated" value={formatDate(selected.metadataUpdatedAt)} />
                {selected.postingUrl && (
                  <Detail
                    name="Posting URL"
                    value={
                      <a href={selected.postingUrl} target="_blank" rel="noopener noreferrer">
                        Open reference
                      </a>
                    }
                  />
                )}
              </dl>
              {!selected.archived && !editing && (
                <button onClick={() => setEditing(true)}>Edit metadata</button>
              )}
              {editing && (
                <JobFormView
                  idPrefix="edit"
                  title="Edit metadata"
                  form={editForm}
                  errors={errors}
                  busy={busy}
                  onChange={setEditForm}
                  onSubmit={saveEdit}
                  onCancel={() => {
                    setEditForm(jobToForm(selected));
                    setEditing(false);
                  }}
                />
              )}
              <section aria-labelledby="snapshots-title">
                <h3 id="snapshots-title">Description snapshots</h3>
                {snapshots.length === 0 ? (
                  <p className="empty-note">No description snapshots yet.</p>
                ) : (
                  <ol className="timeline">
                    {snapshots.map((snapshot) => (
                      <li key={snapshot.id}>
                        <details>
                          <summary>
                            Snapshot {snapshot.sequence} - {label(snapshot.sourceType)} -{' '}
                            {formatDate(snapshot.capturedAt)}
                          </summary>
                          <pre>{snapshot.descriptionText}</pre>
                        </details>
                      </li>
                    ))}
                  </ol>
                )}
                {!selected.archived && (
                  <form
                    className="profile-form compact-form"
                    onSubmit={(event) => void appendSnapshot(event)}
                  >
                    <fieldset disabled={busy}>
                      <legend>Append snapshot</legend>
                      <p className="inline-note">
                        Adding a description creates a new immutable snapshot.
                      </p>
                      <label>
                        Source type
                        <select
                          value={snapshotForm.sourceType}
                          onChange={(e) =>
                            setSnapshotForm({
                              ...snapshotForm,
                              sourceType: e.target.value as JobSourceType,
                            })
                          }
                        >
                          {jobSourceTypes
                            .filter((type) => type !== 'URL_REFERENCE')
                            .map((type) => (
                              <option key={type} value={type}>
                                {label(type)}
                              </option>
                            ))}
                        </select>
                      </label>
                      <TextArea
                        idPrefix="snapshot"
                        label="Description text"
                        value={snapshotForm.descriptionText}
                        maxLength={100000}
                        error={errors.descriptionText}
                        onChange={(value) =>
                          setSnapshotForm({ ...snapshotForm, descriptionText: value })
                        }
                      />
                    </fieldset>
                    <div className="form-actions">
                      <button disabled={busy}>Append snapshot</button>
                    </div>
                  </form>
                )}
              </section>
            </article>
          ) : (
            <p className="empty-note">Select a job to inspect details.</p>
          )}
        </div>
      </div>
    </section>
  );
}

function JobFormView({
  idPrefix,
  title,
  form,
  errors,
  busy,
  includeDescription = false,
  onChange,
  onSubmit,
  onCancel,
}: {
  idPrefix: string;
  title: string;
  form: JobForm;
  errors: Record<string, string>;
  busy: boolean;
  includeDescription?: boolean;
  onChange: (form: JobForm) => void;
  onSubmit: (event: FormEvent) => void;
  onCancel: () => void;
}) {
  return (
    <form
      className="profile-form compact-form"
      onSubmit={(event) => void onSubmit(event)}
      noValidate
    >
      <fieldset disabled={busy}>
        <legend>{title}</legend>
        <TextInput
          idPrefix={idPrefix}
          label="Company name"
          value={form.companyName}
          maxLength={200}
          required
          error={errors.companyName}
          onChange={(value) => onChange({ ...form, companyName: value })}
        />
        <TextInput
          idPrefix={idPrefix}
          label="Job title"
          value={form.jobTitle}
          maxLength={200}
          required
          error={errors.jobTitle}
          onChange={(value) => onChange({ ...form, jobTitle: value })}
        />
        <TextInput
          idPrefix={idPrefix}
          label="Work location"
          value={form.workLocation}
          maxLength={200}
          onChange={(value) => onChange({ ...form, workLocation: value })}
        />
        <label>
          Source type
          <select
            value={form.sourceType}
            onChange={(event) =>
              onChange({ ...form, sourceType: event.target.value as JobSourceType })
            }
          >
            {jobSourceTypes.map((type) => (
              <option key={type} value={type}>
                {label(type)}
              </option>
            ))}
          </select>
        </label>
        <TextInput
          idPrefix={idPrefix}
          label="Posting URL"
          value={form.postingUrl}
          error={errors.postingUrl}
          helper="Stored as a reference only. The app does not open, fetch, or scrape it."
          onChange={(value) => onChange({ ...form, postingUrl: value })}
        />
        <label>
          Employment type
          <select
            value={form.employmentType}
            onChange={(event) =>
              onChange({ ...form, employmentType: event.target.value as EmploymentType | '' })
            }
          >
            <option value="">Not provided</option>
            {employmentTypes.map((type) => (
              <option key={type} value={type}>
                {label(type)}
              </option>
            ))}
          </select>
        </label>
        <TextInput
          idPrefix={idPrefix}
          label="External posting ID"
          value={form.externalPostingId}
          maxLength={200}
          onChange={(value) => onChange({ ...form, externalPostingId: value })}
        />
        <TextInput
          idPrefix={idPrefix}
          label="Date posted"
          type="date"
          value={form.datePosted}
          onChange={(value) => onChange({ ...form, datePosted: value })}
        />
        {includeDescription && (
          <TextArea
            idPrefix={idPrefix}
            label="Description text"
            value={form.descriptionText}
            maxLength={100000}
            error={errors.descriptionText}
            onChange={(value) => onChange({ ...form, descriptionText: value })}
          />
        )}
      </fieldset>
      <div className="form-actions">
        <button disabled={busy}>{busy ? 'Saving...' : title}</button>
        <button className="secondary-button" type="button" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function validateJob(form: JobForm, includeDescription: boolean) {
  const errors: Record<string, string> = {};
  if (!form.companyName.trim()) errors.companyName = 'Company name is required.';
  if (!form.jobTitle.trim()) errors.jobTitle = 'Job title is required.';
  for (const [field, limit] of Object.entries({
    companyName: 200,
    jobTitle: 200,
    workLocation: 200,
    externalPostingId: 200,
  })) {
    if (String(form[field as keyof JobForm]).length > limit)
      errors[field] = `Use ${limit} characters or fewer.`;
  }
  if (form.postingUrl.trim()) {
    try {
      const url = new URL(form.postingUrl.trim());
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password)
        errors.postingUrl = 'Use an HTTP or HTTPS URL without credentials.';
    } catch {
      errors.postingUrl = 'Enter a valid URL.';
    }
  }
  if (form.sourceType === 'URL_REFERENCE' && !form.postingUrl.trim())
    errors.postingUrl = 'URL reference requires a posting URL.';
  if (
    includeDescription &&
    form.sourceType === 'PASTED_DESCRIPTION' &&
    !form.descriptionText.trim()
  )
    errors.descriptionText = 'Pasted description requires description text.';
  if (form.descriptionText.length > 100000)
    errors.descriptionText = 'Use 100000 characters or fewer.';
  return errors;
}

function cleanJob(form: JobForm): CaptureJobRequest {
  return {
    companyName: form.companyName.trim(),
    jobTitle: form.jobTitle.trim(),
    workLocation: optional(form.workLocation),
    postingUrl: optional(form.postingUrl),
    sourceType: form.sourceType,
    employmentType: form.employmentType || null,
    externalPostingId: optional(form.externalPostingId),
    datePosted: form.datePosted || null,
    descriptionText: optional(form.descriptionText),
  };
}

function jobToForm(job: CapturedJob): JobForm {
  return {
    companyName: job.companyName,
    jobTitle: job.jobTitle,
    workLocation: job.workLocation ?? '',
    postingUrl: job.postingUrl ?? '',
    sourceType: job.sourceType,
    employmentType: job.employmentType ?? '',
    externalPostingId: job.externalPostingId ?? '',
    datePosted: job.datePosted ?? '',
    descriptionText: '',
  };
}

function TextInput({
  idPrefix,
  label,
  value,
  onChange,
  maxLength,
  required,
  helper,
  error,
  type = 'text',
}: {
  idPrefix: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  maxLength?: number | undefined;
  required?: boolean | undefined;
  helper?: string | undefined;
  error?: string | undefined;
  type?: string | undefined;
}) {
  const id = `${idPrefix}-${fieldId(label)}`;
  return (
    <label htmlFor={id}>
      {label}
      <input
        id={id}
        type={type}
        required={required}
        maxLength={maxLength}
        value={value}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={`${id}-help ${id}-error`}
        onChange={(event) => onChange(event.target.value)}
      />
      <FieldHelp id={id} helper={helper} value={value} maxLength={maxLength} error={error} />
    </label>
  );
}

function TextArea(props: Omit<Parameters<typeof TextInput>[0], 'type'>) {
  const id = `${props.idPrefix}-${fieldId(props.label)}`;
  return (
    <label htmlFor={id}>
      {props.label}
      <textarea
        id={id}
        required={props.required}
        maxLength={props.maxLength}
        value={props.value}
        aria-invalid={props.error ? 'true' : undefined}
        aria-describedby={`${id}-help ${id}-error`}
        onChange={(event) => props.onChange(event.target.value)}
      />
      <FieldHelp
        id={id}
        helper={props.helper}
        value={props.value}
        maxLength={props.maxLength}
        error={props.error}
      />
    </label>
  );
}

function FieldHelp({
  id,
  helper,
  value,
  maxLength,
  error,
}: {
  id: string;
  helper?: string | undefined;
  value: string;
  maxLength?: number | undefined;
  error?: string | undefined;
}) {
  return (
    <>
      {(helper || maxLength) && (
        <span className="helper" id={`${id}-help`}>
          {helper}
          {maxLength ? ` ${value.length}/${maxLength}` : ''}
        </span>
      )}
      <span className="field-error" id={`${id}-error`}>
        {error}
      </span>
    </>
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

function optional(value: string) {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function handleWriteError(
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
        ? 'This job changed elsewhere. Review your unsaved values, then reload the latest job.'
        : fallback,
    );
  } else setFailure(readableError(error, fallback));
}

function readableError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    if (error.status === 400)
      return 'Some job information is invalid. Review the form and try again.';
    if (error.status === 403) return 'You do not have permission to perform that job action.';
    if (error.status === 404) return 'That private job was not found. Refresh and try again.';
  }
  return fallback;
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

function fieldId(labelText: string) {
  return `job-${labelText
    .toLowerCase()
    .replaceAll(/[^a-z0-9]+/g, '-')
    .replaceAll(/^-|-$/g, '')}`;
}
