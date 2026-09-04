import {
  type FormEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { ApiError } from '../../api/client';
import {
  allowedTransitions,
  applicationsApi,
  applicationStatuses,
  type ApplicationStatus,
  type ApplicationStatusHistory,
  type JobApplication,
} from '../../api/applications';
import { jobsApi, type CapturedJob } from '../../api/jobs';

type DueFilter = '' | 'overdue' | 'today' | 'upcoming' | 'no_due';

export function ApplicationsWorkspace({ onExpired }: { onExpired: () => void }) {
  const [archived, setArchived] = useState(false);
  const [statusFilter, setStatusFilter] = useState<ApplicationStatus | ''>('');
  const [applications, setApplications] = useState<JobApplication[]>([]);
  const [jobs, setJobs] = useState<CapturedJob[]>([]);
  const [selectedId, setSelectedId] = useState<string>();
  const [selected, setSelected] = useState<JobApplication>();
  const [history, setHistory] = useState<ApplicationStatusHistory[]>([]);
  const [mode, setMode] = useState<'loading' | 'ready'>('loading');
  const [message, setMessage] = useState('');
  const [failure, setFailure] = useState('');
  const [conflict, setConflict] = useState(false);
  const [busy, setBusy] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState({
    jobId: '',
    privateNotes: '',
    nextActionText: '',
    nextActionDueDate: '',
  });
  const [editForm, setEditForm] = useState({
    privateNotes: '',
    nextActionText: '',
    nextActionDueDate: '',
  });
  const [editing, setEditing] = useState(false);
  const [transitionForm, setTransitionForm] = useState({
    targetStatus: '' as ApplicationStatus | '',
    appliedAt: '',
    note: '',
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [searchText, setSearchText] = useState('');
  const [dueFilter, setDueFilter] = useState<DueFilter>('');
  const selectedLoadSequence = useRef(0);

  const jobById = useMemo(() => new Map(jobs.map((job) => [job.id, job])), [jobs]);
  const applicationJobIds = useMemo(
    () => new Set(applications.map((application) => application.jobId)),
    [applications],
  );
  const availableJobs = jobs.filter((job) => !job.archived && !applicationJobIds.has(job.id));
  const filteredApplications = useMemo(
    () =>
      applications.filter((application) =>
        matchesApplicationFilters(
          application,
          jobById.get(application.jobId),
          searchText,
          dueFilter,
        ),
      ),
    [applications, dueFilter, jobById, searchText],
  );
  const localFiltersActive = searchText.trim() !== '' || dueFilter !== '';

  const loadLists = useCallback(async () => {
    setMode('loading');
    setFailure('');
    try {
      const [loadedApplications, activeJobs, archivedJobs] = await Promise.all([
        applicationsApi.listApplications({ archived, status: statusFilter, limit: 100 }),
        jobsApi.listJobs({ archived: false, limit: 100 }),
        jobsApi.listJobs({ archived: true, limit: 100 }),
      ]);
      setApplications(loadedApplications);
      setJobs([...activeJobs, ...archivedJobs]);
      setSelectedId((current) =>
        current && loadedApplications.some((application) => application.id === current)
          ? current
          : loadedApplications[0]?.id,
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onExpired();
      else setFailure(readableError(error, 'Applications are temporarily unavailable.'));
    } finally {
      setMode('ready');
    }
  }, [archived, onExpired, statusFilter]);

  const loadSelected = useCallback(async () => {
    const loadSequence = ++selectedLoadSequence.current;
    if (!selectedId) {
      setSelected(undefined);
      setHistory([]);
      return;
    }
    setFailure('');
    try {
      const [application, loadedHistory] = await Promise.all([
        applicationsApi.getApplication(selectedId),
        applicationsApi.listHistory(selectedId, 100),
      ]);
      if (loadSequence !== selectedLoadSequence.current) return;
      setSelected(application);
      setEditForm({
        privateNotes: application.privateNotes ?? '',
        nextActionText: application.nextActionText ?? '',
        nextActionDueDate: application.nextActionDueDate ?? '',
      });
      setHistory(loadedHistory);
      setTransitionForm({ targetStatus: '', appliedAt: '', note: '' });
      setEditing(false);
      setConflict(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onExpired();
      else setFailure(readableError(error, 'The selected application could not be loaded.'));
    }
  }, [onExpired, selectedId]);

  useEffect(() => {
    const load = async () => {
      await loadLists();
    };
    void load();
  }, [loadLists]);

  useEffect(() => {
    const load = async () => {
      await loadSelected();
    };
    void load();
  }, [loadSelected]);

  const create = async (event: FormEvent) => {
    event.preventDefault();
    const validation = validateMetadata(createForm);
    if (!createForm.jobId) validation.jobId = 'Choose an active captured job.';
    setErrors(validation);
    if (Object.keys(validation).length > 0) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const created = await applicationsApi.createApplication({
        jobId: createForm.jobId,
        privateNotes: optional(createForm.privateNotes),
        nextActionText: optional(createForm.nextActionText),
        nextActionDueDate: createForm.nextActionDueDate || null,
      });
      setCreateOpen(false);
      setCreateForm({ jobId: '', privateNotes: '', nextActionText: '', nextActionDueDate: '' });
      setApplications((current) => [created, ...current]);
      setSelectedId(created.id);
      setSelected(created);
      setMessage('Draft application created. It has not been submitted.');
      setHistory(await applicationsApi.listHistory(created.id, 100));
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        await loadLists();
        setFailure('An application already exists for that job. The list was refreshed.');
      } else
        handleWriteError(
          error,
          onExpired,
          setFailure,
          setConflict,
          'The application was not created.',
        );
    } finally {
      setBusy(false);
    }
  };

  const saveMetadata = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected) return;
    const validation = validateMetadata(editForm);
    if (terminal(selected.status) && editForm.nextActionText.trim())
      validation.nextActionText = 'Terminal applications cannot have next actions.';
    setErrors(validation);
    if (Object.keys(validation).length > 0) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved = await applicationsApi.updateApplication(selected.id, {
        privateNotes: optional(editForm.privateNotes),
        nextActionText: optional(editForm.nextActionText),
        nextActionDueDate: editForm.nextActionDueDate || null,
        expectedVersion: selected.version,
      });
      replaceApplication(saved);
      setEditing(false);
      setConflict(false);
      setMessage('Notes and next action saved.');
    } catch (error) {
      handleWriteError(error, onExpired, setFailure, setConflict, 'The application was not saved.');
    } finally {
      setBusy(false);
    }
  };

  const transition = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected || !transitionForm.targetStatus) return;
    const validation: Record<string, string> = {};
    if (
      selected.status === 'INTERVIEWING' &&
      transitionForm.targetStatus === 'INTERVIEWING' &&
      !transitionForm.note.trim()
    ) {
      validation.note = 'Record a meaningful note for another interview stage.';
    }
    if (Object.keys(validation).length > 0) {
      setErrors(validation);
      return;
    }
    const target = transitionForm.targetStatus;
    if (['APPLIED', 'OFFER', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'].includes(target)) {
      const text =
        target === 'APPLIED'
          ? 'Record this application as applied? The system does not submit it.'
          : target === 'WITHDRAWN'
            ? 'Record this application as withdrawn? Active next actions will be cleared.'
            : `Record this user-declared status as ${label(target)}?`;
      if (!window.confirm(text)) return;
    }
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const request = {
        targetStatus: target,
        expectedVersion: selected.version,
        note: optional(transitionForm.note),
        ...(selected.status === 'READY_TO_APPLY' && target === 'APPLIED' && transitionForm.appliedAt
          ? { appliedAt: new Date(transitionForm.appliedAt).toISOString() }
          : {}),
      };
      const saved = await applicationsApi.transitionApplication(selected.id, request);
      replaceApplication(saved);
      setHistory(await applicationsApi.listHistory(saved.id, 100));
      setTransitionForm({ targetStatus: '', appliedAt: '', note: '' });
      setConflict(false);
      setMessage('Status recorded from your explicit action.');
    } catch (error) {
      handleWriteError(
        error,
        onExpired,
        setFailure,
        setConflict,
        'The status change was not recorded.',
      );
    } finally {
      setBusy(false);
    }
  };

  const archiveState = async (action: 'archive' | 'restore') => {
    if (!selected) return;
    const verb = action === 'archive' ? 'Archive' : 'Restore';
    if (!window.confirm(`${verb} this application? Status and history are preserved.`)) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved =
        action === 'archive'
          ? await applicationsApi.archiveApplication(selected.id, selected.version)
          : await applicationsApi.restoreApplication(selected.id, selected.version);
      replaceApplication(saved);
      setApplications((current) => current.filter((application) => application.id !== saved.id));
      setMessage(action === 'archive' ? 'Application archived.' : 'Application restored.');
      await loadLists();
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

  const replaceApplication = (application: JobApplication) => {
    setSelected(application);
    setApplications((current) =>
      current.map((item) => (item.id === application.id ? application : item)),
    );
    setEditForm({
      privateNotes: application.privateNotes ?? '',
      nextActionText: application.nextActionText ?? '',
      nextActionDueDate: application.nextActionDueDate ?? '',
    });
  };

  return (
    <section className="workspace-section" aria-labelledby="applications-title">
      <div className="section-heading">
        <p className="eyebrow">Application tracker</p>
        <h1 id="applications-title">Applications</h1>
        <p>
          Statuses are user-declared. This system never submits applications or changes status
          automatically.
        </p>
      </div>
      <p className="status-line" role="status" aria-live="polite">
        {message}
      </p>
      {failure && (
        <div className="alert" role="alert">
          <p>{failure}</p>
          {conflict && (
            <button onClick={() => void loadSelected()}>Reload latest application</button>
          )}
        </div>
      )}
      <div className="workspace-grid">
        <div>
          <div className="toolbar">
            <button onClick={() => setCreateOpen((open) => !open)}>Create application</button>
            <button
              className="secondary-button"
              onClick={() => setArchived(false)}
              aria-pressed={!archived}
            >
              Active applications
            </button>
            <button
              className="secondary-button"
              onClick={() => setArchived(true)}
              aria-pressed={archived}
            >
              Archived applications
            </button>
          </div>
          <label className="filter-control">
            Status
            <select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value as ApplicationStatus | '')}
            >
              <option value="">All statuses</option>
              {applicationStatuses.map((status) => (
                <option key={status} value={status}>
                  {label(status)}
                </option>
              ))}
            </select>
          </label>
          <div className="filter-bar" aria-label="Application filters">
            <label>
              Search loaded applications
              <input
                value={searchText}
                onChange={(event) => setSearchText(event.target.value)}
                placeholder="Company, title, notes, or next action"
              />
            </label>
            <label>
              Due state
              <select
                value={dueFilter}
                onChange={(event) => setDueFilter(event.target.value as DueFilter)}
              >
                <option value="">All due states</option>
                <option value="overdue">Overdue</option>
                <option value="today">Due today</option>
                <option value="upcoming">Upcoming</option>
                <option value="no_due">No due date</option>
              </select>
            </label>
            <button
              className="secondary-button"
              type="button"
              disabled={!localFiltersActive && !statusFilter}
              onClick={() => {
                setSearchText('');
                setDueFilter('');
                setStatusFilter('');
              }}
            >
              Clear filters
            </button>
          </div>
          <p className="inline-note" role="status" aria-live="polite">
            Showing {filteredApplications.length} of {applications.length} loaded{' '}
            {archived ? 'archived' : 'active'} applications.
          </p>
          {createOpen && (
            <form
              className="profile-form compact-form"
              onSubmit={(event) => void create(event)}
              noValidate
            >
              <fieldset disabled={busy}>
                <legend>Create application</legend>
                <p className="inline-note">
                  Creating an application starts it as Draft. It does not submit the application.
                </p>
                <label>
                  Captured job
                  <select
                    value={createForm.jobId}
                    aria-invalid={errors.jobId ? 'true' : undefined}
                    onChange={(event) =>
                      setCreateForm({ ...createForm, jobId: event.target.value })
                    }
                  >
                    <option value="">Choose a job</option>
                    {availableJobs.map((job) => (
                      <option key={job.id} value={job.id}>
                        {job.companyName} - {job.jobTitle}
                      </option>
                    ))}
                  </select>
                  <span className="field-error">{errors.jobId}</span>
                </label>
                <MetadataFields values={createForm} errors={errors} onChange={setCreateForm} />
              </fieldset>
              <div className="form-actions">
                <button disabled={busy}>Create draft application</button>
                <button
                  className="secondary-button"
                  type="button"
                  onClick={() => setCreateOpen(false)}
                >
                  Cancel
                </button>
              </div>
            </form>
          )}
          {mode === 'loading' ? (
            <p>Loading applications...</p>
          ) : applications.length === 0 ? (
            <p className="empty-note">
              {statusFilter
                ? 'No applications match this filter.'
                : archived
                  ? 'No archived applications.'
                  : 'No active applications yet.'}
            </p>
          ) : filteredApplications.length === 0 ? (
            <p className="empty-note">No loaded applications match these filters.</p>
          ) : (
            <ul
              className="record-list"
              aria-label={archived ? 'Archived applications' : 'Active applications'}
            >
              {filteredApplications.map((application) => {
                const job = jobById.get(application.jobId);
                return (
                  <li key={application.id}>
                    <button
                      className="record-button"
                      aria-current={selectedId === application.id ? 'true' : undefined}
                      onClick={() => setSelectedId(application.id)}
                    >
                      <strong>{job?.companyName ?? 'Job unavailable'}</strong>
                      <span>{job?.jobTitle ?? application.jobId}</span>
                      <span>{label(application.status)}</span>
                      <span>
                        {application.archived
                          ? 'Archived'
                          : `Changed ${formatDate(application.statusChangedAt)}`}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
        <div>
          {selected ? (
            <article className="detail-panel">
              <div className="fact-title">
                <div>
                  <h2>{jobById.get(selected.jobId)?.companyName ?? 'Job unavailable'}</h2>
                  <p>{jobById.get(selected.jobId)?.jobTitle ?? selected.jobId}</p>
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
                <Detail name="Current status" value={label(selected.status)} />
                <Detail
                  name="Applied"
                  value={selected.appliedAt ? formatDate(selected.appliedAt) : 'Not recorded'}
                />
                <Detail name="Next action" value={selected.nextActionText || 'None'} />
                <Detail name="Due date" value={selected.nextActionDueDate || 'Not set'} />
                <Detail name="Private notes" value={selected.privateNotes || 'None'} />
                <Detail name="Status changed" value={formatDate(selected.statusChangedAt)} />
                <Detail name="Archived" value={selected.archived ? 'Yes' : 'No'} />
              </dl>
              {!selected.archived && !editing && (
                <button onClick={() => setEditing(true)}>Edit notes and next action</button>
              )}
              {editing && (
                <form
                  aria-label="Notes and next action"
                  className="profile-form compact-form"
                  onSubmit={(event) => void saveMetadata(event)}
                  noValidate
                >
                  <fieldset disabled={busy}>
                    <legend>Notes and next action</legend>
                    {terminal(selected.status) && (
                      <p className="warning">
                        Terminal applications cannot have active next actions.
                      </p>
                    )}
                    <MetadataFields values={editForm} errors={errors} onChange={setEditForm} />
                  </fieldset>
                  <div className="form-actions">
                    <button disabled={busy}>Save application notes</button>
                    <button
                      className="secondary-button"
                      type="button"
                      onClick={() => {
                        setEditForm({
                          privateNotes: selected.privateNotes ?? '',
                          nextActionText: selected.nextActionText ?? '',
                          nextActionDueDate: selected.nextActionDueDate ?? '',
                        });
                        setEditing(false);
                      }}
                    >
                      Cancel
                    </button>
                  </div>
                </form>
              )}
              {!selected.archived && allowedTransitions[selected.status].length > 0 && (
                <form
                  className="profile-form compact-form"
                  onSubmit={(event) => void transition(event)}
                  noValidate
                >
                  <fieldset disabled={busy}>
                    <legend>Status transition</legend>
                    <p className="inline-note">
                      Every status change is recorded only after your deliberate action.
                    </p>
                    <label>
                      Next status
                      <select
                        required
                        value={transitionForm.targetStatus}
                        onChange={(event) =>
                          setTransitionForm({
                            ...transitionForm,
                            targetStatus: event.target.value as ApplicationStatus | '',
                          })
                        }
                      >
                        <option value="">Choose a status</option>
                        {allowedTransitions[selected.status].map((status) => (
                          <option key={status} value={status}>
                            {status === 'INTERVIEWING' && selected.status === 'INTERVIEWING'
                              ? 'Record another interview stage'
                              : label(status)}
                          </option>
                        ))}
                      </select>
                    </label>
                    {selected.status === 'READY_TO_APPLY' &&
                      transitionForm.targetStatus === 'APPLIED' && (
                        <TextInput
                          label="Applied date and time"
                          type="datetime-local"
                          value={transitionForm.appliedAt}
                          helper="Optional. If omitted, the server records the current time."
                          onChange={(value) =>
                            setTransitionForm({ ...transitionForm, appliedAt: value })
                          }
                        />
                      )}
                    {transitionForm.targetStatus === 'WITHDRAWN' && (
                      <p className="warning">
                        Withdrawal may happen before or after applying. Active next actions will be
                        cleared.
                      </p>
                    )}
                    <TextArea
                      label="History note"
                      value={transitionForm.note}
                      maxLength={1000}
                      error={errors.note}
                      onChange={(value) => setTransitionForm({ ...transitionForm, note: value })}
                    />
                  </fieldset>
                  <div className="form-actions">
                    <button disabled={busy || !transitionForm.targetStatus}>Record status</button>
                  </div>
                </form>
              )}
              <section aria-labelledby="history-title">
                <h3 id="history-title">Status history</h3>
                <ol className="timeline">
                  {history.map((event) => (
                    <li key={event.id}>
                      <strong>
                        {event.previousStatus
                          ? `${label(event.previousStatus)} to ${label(event.newStatus)}`
                          : 'Application created'}
                      </strong>
                      <span>{formatDate(event.effectiveAt)}</span>
                      {event.note && <p>{event.note}</p>}
                    </li>
                  ))}
                </ol>
              </section>
            </article>
          ) : (
            <p className="empty-note">Select an application to inspect details.</p>
          )}
        </div>
      </div>
    </section>
  );
}

function MetadataFields<
  T extends { privateNotes: string; nextActionText: string; nextActionDueDate: string },
>({
  values,
  errors,
  onChange,
}: {
  values: T;
  errors: Record<string, string>;
  onChange: (values: T) => void;
}) {
  return (
    <>
      <TextArea
        label="Private notes"
        value={values.privateNotes}
        maxLength={4000}
        onChange={(value) => onChange({ ...values, privateNotes: value })}
      />
      <TextInput
        label="Next action"
        value={values.nextActionText}
        maxLength={500}
        error={errors.nextActionText}
        onChange={(value) => onChange({ ...values, nextActionText: value })}
      />
      <TextInput
        label="Next action due date"
        type="date"
        value={values.nextActionDueDate}
        error={errors.nextActionDueDate}
        onChange={(value) => onChange({ ...values, nextActionDueDate: value })}
      />
    </>
  );
}

function validateMetadata(values: {
  privateNotes: string;
  nextActionText: string;
  nextActionDueDate: string;
}) {
  const errors: Record<string, string> = {};
  if (values.privateNotes.length > 4000) errors.privateNotes = 'Use 4000 characters or fewer.';
  if (values.nextActionText.length > 500) errors.nextActionText = 'Use 500 characters or fewer.';
  if (values.nextActionDueDate && !values.nextActionText.trim())
    errors.nextActionDueDate = 'Due date requires next-action text.';
  return errors;
}

function TextInput({
  label,
  value,
  onChange,
  maxLength,
  helper,
  error,
  type = 'text',
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  maxLength?: number | undefined;
  helper?: string | undefined;
  error?: string | undefined;
  type?: string | undefined;
}) {
  const id = fieldId(label);
  return (
    <label htmlFor={id}>
      {label}
      <input
        id={id}
        type={type}
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
  const id = fieldId(props.label);
  return (
    <label htmlFor={id}>
      {props.label}
      <textarea
        id={id}
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
        ? 'This application changed elsewhere. Review your unsaved values, then reload latest.'
        : fallback,
    );
  } else setFailure(readableError(error, fallback));
}

function readableError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    if (error.status === 400)
      return 'Some application information is invalid. Review the form and try again.';
    if (error.status === 403)
      return 'You do not have permission to perform that application action.';
    if (error.status === 404)
      return 'That private application or job was not found. Refresh and try again.';
  }
  return fallback;
}

function terminal(status: ApplicationStatus) {
  return status === 'ACCEPTED' || status === 'REJECTED' || status === 'WITHDRAWN';
}

function matchesApplicationFilters(
  application: JobApplication,
  job: CapturedJob | undefined,
  searchText: string,
  dueFilter: DueFilter,
) {
  const query = normalizeText(searchText);
  if (
    query &&
    ![
      job?.companyName ?? '',
      job?.jobTitle ?? '',
      application.privateNotes ?? '',
      application.nextActionText ?? '',
    ].some((value) => normalizeText(value).includes(query))
  ) {
    return false;
  }
  if (!dueFilter) return true;
  return dueState(application.nextActionDueDate) === dueFilter;
}

function dueState(value: string | null | undefined): DueFilter {
  if (!value) return 'no_due';
  const today = new Date();
  const todayKey = dateKey(today);
  if (value < todayKey) return 'overdue';
  if (value === todayKey) return 'today';
  return 'upcoming';
}

function optional(value: string) {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

function normalizeText(value: string) {
  return value.trim().toLowerCase().replaceAll(/\s+/g, ' ');
}

function dateKey(value: Date) {
  const month = String(value.getMonth() + 1).padStart(2, '0');
  const day = String(value.getDate()).padStart(2, '0');
  return `${value.getFullYear()}-${month}-${day}`;
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
  return `application-${labelText
    .toLowerCase()
    .replaceAll(/[^a-z0-9]+/g, '-')
    .replaceAll(/^-|-$/g, '')}`;
}
