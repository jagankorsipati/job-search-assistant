import { type FormEvent, type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { ApiError, authApi, type Identity, type ManagedAccount } from './api/auth';
import {
  careerFactCategories,
  careerFactStatuses,
  profileApi,
  type CandidateProfile,
  type CareerFact,
  type CareerFactCategory,
  type CareerFactFields,
  type CareerFactStatus,
  type ProfileFields,
} from './api/profile';

type View = 'loading' | 'login' | 'shell' | 'invite' | 'admin-invitations' | 'admin-accounts';
type Workspace = 'dashboard' | 'profile';
const upcomingItems = ['Jobs', 'Documents', 'Applications'] as const;

const blankProfile: ProfileFields = {
  professionalDisplayName: '',
  professionalHeadline: '',
  careerSummary: '',
  locationPreference: '',
  targetRoles: '',
  workAuthorizationStatement: '',
  workLocationPreferences: '',
};

const blankFact: CareerFactFields = {
  category: 'EMPLOYMENT',
  factualContent: '',
  organization: '',
  title: '',
  location: '',
  startedOn: '',
  endedOn: '',
  ongoing: false,
};

const profileLimits = {
  professionalDisplayName: 120,
  professionalHeadline: 160,
  careerSummary: 2000,
  locationPreference: 160,
  targetRoles: 1000,
  workAuthorizationStatement: 500,
  workLocationPreferences: 500,
};

const factLimits = {
  factualContent: 2000,
  organization: 200,
  title: 200,
  location: 160,
};

export function App() {
  const [view, setView] = useState<View>('loading');
  const [workspace, setWorkspace] = useState<Workspace>(
    window.location.pathname === '/profile' ? 'profile' : 'dashboard',
  );
  const [identity, setIdentity] = useState<Identity>();
  const [inviteToken, setInviteToken] = useState('');

  useEffect(() => {
    const fragment = window.location.hash.startsWith('#invite=')
      ? window.location.hash.slice(8)
      : '';
    if (fragment !== '') {
      window.history.replaceState(null, '', '/invite');
      const token = decodeURIComponent(fragment);
      queueMicrotask(() => {
        setInviteToken(token);
        setView('invite');
      });
      return;
    }
    void authApi
      .me()
      .then((me) => {
        setIdentity(me);
        setView(
          me.role === 'ADMIN' && window.location.pathname === '/admin/invitations'
            ? 'admin-invitations'
            : me.role === 'ADMIN' && window.location.pathname === '/admin/accounts'
              ? 'admin-accounts'
              : 'shell',
        );
      })
      .catch(() => setView(window.location.pathname === '/invite' ? 'invite' : 'login'));
  }, []);

  const navigate = (next: View, path: string) => {
    window.history.pushState(null, '', path);
    setView(next);
  };
  const openWorkspace = (next: Workspace) => {
    setWorkspace(next);
    navigate('shell', next === 'profile' ? '/profile' : '/');
  };
  const expireSession = () => {
    setIdentity(undefined);
    navigate('login', '/login');
  };

  if (view === 'loading')
    return (
      <main className="auth-page" aria-live="polite">
        <p>Restoring your session...</p>
      </main>
    );
  if (view === 'login')
    return (
      <Login
        onSuccess={(me) => {
          setIdentity(me);
          openWorkspace(window.location.pathname === '/profile' ? 'profile' : 'dashboard');
        }}
        onInvite={() => navigate('invite', '/invite')}
      />
    );
  if (view === 'invite')
    return (
      <AcceptInvitation
        initialToken={inviteToken}
        onDone={() => {
          setInviteToken('');
          navigate('login', '/login');
        }}
      />
    );
  if (view === 'admin-invitations' && identity?.role === 'ADMIN')
    return <CreateInvitation onBack={() => openWorkspace('dashboard')} onExpired={expireSession} />;
  if (view === 'admin-accounts' && identity?.role === 'ADMIN')
    return <ManageAccounts onBack={() => openWorkspace('dashboard')} onExpired={expireSession} />;
  return (
    <Shell
      identity={identity!}
      workspace={workspace}
      onWorkspace={openWorkspace}
      onInvitations={() => navigate('admin-invitations', '/admin/invitations')}
      onAccounts={() => navigate('admin-accounts', '/admin/accounts')}
      onExpired={expireSession}
      onLogout={() =>
        void authApi.logout().finally(() => {
          setIdentity(undefined);
          navigate('login', '/login');
        })
      }
    />
  );
}

function Login({
  onSuccess,
  onInvite,
}: {
  onSuccess: (identity: Identity) => void;
  onInvite: () => void;
}) {
  const [loginName, setLoginName] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setMessage('');
    try {
      onSuccess(await authApi.login(loginName, password));
      setPassword('');
    } catch (failure) {
      setMessage(
        failure instanceof ApiError && failure.status === 429
          ? `Too many attempts. Try again${failure.retryAfter ? ` in ${failure.retryAfter} seconds` : ' later'}.`
          : 'Login name or password is incorrect.',
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <AuthFrame title="Sign in to your private workspace">
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Login name
          <input
            required
            autoComplete="username"
            value={loginName}
            onChange={(e) => setLoginName(e.target.value)}
          />
        </label>
        <label>
          Password
          <input
            required
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <p role={message ? 'alert' : undefined}>{message}</p>
        <button disabled={busy}>{busy ? 'Signing in...' : 'Sign in'}</button>
      </form>
      <button className="text-button" onClick={onInvite}>
        Accept an invitation
      </button>
    </AuthFrame>
  );
}

function AcceptInvitation({ initialToken, onDone }: { initialToken: string; onDone: () => void }) {
  const [token, setToken] = useState(initialToken);
  const [loginName, setLoginName] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (password !== confirmation) {
      setMessage('Passwords do not match.');
      return;
    }
    setBusy(true);
    setMessage('');
    try {
      await authApi.acceptInvitation({ token, loginName, displayName, password });
      setToken('');
      setPassword('');
      setConfirmation('');
      onDone();
    } catch (failure) {
      setMessage(
        failure instanceof ApiError && failure.code === 'password_rejected'
          ? 'Choose a password that is not commonly used and does not contain your account details.'
          : 'This invitation is invalid or no longer available.',
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <AuthFrame title="Create your household account">
      <form onSubmit={(e) => void submit(e)}>
        <p>Use 15-128 characters. Long, unique passphrases are encouraged.</p>
        {initialToken === '' && (
          <label>
            Invitation token
            <input
              required
              autoComplete="off"
              value={token}
              onChange={(e) => setToken(e.target.value)}
            />
          </label>
        )}
        <label>
          Display name
          <input
            required
            autoComplete="name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
        </label>
        <label>
          Login name
          <input
            required
            autoComplete="username"
            value={loginName}
            onChange={(e) => setLoginName(e.target.value)}
          />
        </label>
        <label>
          Password
          <input
            required
            type="password"
            minLength={15}
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <label>
          Confirm password
          <input
            required
            type="password"
            autoComplete="new-password"
            value={confirmation}
            onChange={(e) => setConfirmation(e.target.value)}
          />
        </label>
        <p role={message ? 'alert' : undefined}>{message}</p>
        <button disabled={busy}>{busy ? 'Creating account...' : 'Create account'}</button>
      </form>
    </AuthFrame>
  );
}

function Shell({
  identity,
  workspace,
  onWorkspace,
  onInvitations,
  onAccounts,
  onExpired,
  onLogout,
}: {
  identity: Identity;
  workspace: Workspace;
  onWorkspace: (workspace: Workspace) => void;
  onInvitations: () => void;
  onAccounts: () => void;
  onExpired: () => void;
  onLogout: () => void;
}) {
  return (
    <div className="page-shell">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <header className="site-header">
        <Brand />
        <p>Signed in as {identity.role.toLowerCase()}</p>
        <button onClick={onLogout}>Sign out</button>
      </header>
      <div className="workspace-layout">
        <aside className="sidebar">
          <nav aria-label="Primary navigation">
            <ul className="nav-list">
              <li>
                <button
                  className="nav-item nav-button"
                  aria-current={workspace === 'dashboard' ? 'page' : undefined}
                  onClick={() => onWorkspace('dashboard')}
                >
                  Dashboard
                  <span className="upcoming-label">Today</span>
                </button>
              </li>
              <li>
                <button
                  className="nav-item nav-button"
                  aria-current={workspace === 'profile' ? 'page' : undefined}
                  onClick={() => onWorkspace('profile')}
                >
                  Profile
                </button>
              </li>
              {upcomingItems.map((item) => (
                <li key={item}>
                  <span className="nav-item" aria-disabled="true">
                    {item}
                    <span className="upcoming-label">Upcoming</span>
                  </span>
                </li>
              ))}
            </ul>
          </nav>
          {identity.role === 'ADMIN' && (
            <div className="admin-actions" aria-label="Household administration">
              <button onClick={onInvitations}>Create member invitation</button>
              <button onClick={onAccounts}>Manage household members</button>
            </div>
          )}
        </aside>
        <main id="main-content" className="main-content">
          {workspace === 'profile' ? <ProfileWorkspace onExpired={onExpired} /> : <Dashboard />}
        </main>
      </div>
    </div>
  );
}

function Dashboard() {
  return (
    <section className="hero">
      <p className="eyebrow">Private by default · Truth before optimization</p>
      <h1>Your job-search workspace.</h1>
      <p className="hero-copy">
        Product sections remain upcoming while identity and profile foundations are completed
        carefully.
      </p>
    </section>
  );
}

function ProfileWorkspace({ onExpired }: { onExpired: () => void }) {
  const [profile, setProfile] = useState<CandidateProfile>();
  const [profileMode, setProfileMode] = useState<'loading' | 'empty' | 'view' | 'edit'>('loading');
  const [profileForm, setProfileForm] = useState<ProfileFields>(blankProfile);
  const [profileErrors, setProfileErrors] = useState<Record<string, string>>({});
  const [facts, setFacts] = useState<CareerFact[]>([]);
  const [factMode, setFactMode] = useState<'idle' | 'create'>('idle');
  const [editingFactId, setEditingFactId] = useState<string>();
  const [factForm, setFactForm] = useState<CareerFactFields>(blankFact);
  const [factErrors, setFactErrors] = useState<Record<string, string>>({});
  const [categoryFilter, setCategoryFilter] = useState<'' | CareerFactCategory>('');
  const [statusFilter, setStatusFilter] = useState<'' | CareerFactStatus>('');
  const [message, setMessage] = useState('');
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [attestingFactId, setAttestingFactId] = useState<string>();
  const [attested, setAttested] = useState(false);

  const loadProfile = useCallback(async () => {
    setFailure('');
    setConflict(false);
    try {
      const loaded = await profileApi.getProfile();
      setProfile(loaded);
      setProfileForm(profileToFields(loaded));
      setProfileMode('view');
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onExpired();
      else if (error instanceof ApiError && error.status === 404) {
        setProfile(undefined);
        setProfileForm(blankProfile);
        setProfileMode('empty');
      } else setFailure(readableError(error, 'Candidate profile is temporarily unavailable.'));
    }
  }, [onExpired]);

  const loadFacts = useCallback(async () => {
    setFailure('');
    try {
      setFacts(
        await profileApi.listFacts({
          category: categoryFilter || undefined,
          status: statusFilter || undefined,
          limit: 100,
        }),
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) onExpired();
      else setFailure(readableError(error, 'Career facts are temporarily unavailable.'));
    }
  }, [categoryFilter, onExpired, statusFilter]);

  useEffect(() => {
    queueMicrotask(() => void loadProfile());
  }, [loadProfile]);

  useEffect(() => {
    queueMicrotask(() => void loadFacts());
  }, [loadFacts]);

  const saveProfile = async (event: FormEvent) => {
    event.preventDefault();
    const errors = validateProfile(profileForm);
    setProfileErrors(errors);
    if (Object.keys(errors).length > 0) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved =
        profileMode === 'empty'
          ? await profileApi.createProfile(cleanProfile(profileForm))
          : await profileApi.updateProfile({
              ...cleanProfile(profileForm),
              expectedVersion: profile?.version ?? 0,
            });
      setProfile(saved);
      setProfileForm(profileToFields(saved));
      setProfileMode('view');
      setConflict(false);
      setMessage('Profile saved.');
    } catch (error) {
      handleWriteError(error, onExpired, setFailure, setConflict);
    } finally {
      setBusy(false);
    }
  };

  const beginCreateFact = () => {
    setFactMode('create');
    setEditingFactId(undefined);
    setFactForm(blankFact);
    setFactErrors({});
    setFailure('');
  };

  const beginEditFact = (fact: CareerFact) => {
    setFactMode('idle');
    setEditingFactId(fact.id);
    setFactForm(factToFields(fact));
    setFactErrors({});
    setFailure('');
  };

  const saveFact = async (event: FormEvent) => {
    event.preventDefault();
    const errors = validateFact(factForm);
    setFactErrors(errors);
    if (Object.keys(errors).length > 0) return;
    const existing = facts.find((fact) => fact.id === editingFactId);
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved =
        editingFactId && existing
          ? await profileApi.updateFact(editingFactId, {
              ...cleanFact(factForm),
              expectedVersion: existing.version,
            })
          : await profileApi.createFact(cleanFact(factForm));
      upsertFact(saved, setFacts);
      setFactMode('idle');
      setEditingFactId(undefined);
      setFactForm(blankFact);
      setConflict(false);
      setMessage(editingFactId ? 'Career fact saved.' : 'Draft career fact added.');
    } catch (error) {
      handleWriteError(error, onExpired, setFailure, setConflict);
    } finally {
      setBusy(false);
    }
  };

  const lifecycle = async (fact: CareerFact, action: 'confirm' | 'archive' | 'restore') => {
    if (action === 'archive' && !window.confirm('Archive this career fact?')) return;
    if (action === 'restore' && !window.confirm('Restore this career fact to draft?')) return;
    setBusy(true);
    setFailure('');
    setMessage('');
    try {
      const saved =
        action === 'confirm'
          ? await profileApi.confirmFact(fact.id, {
              expectedVersion: fact.version,
              confirmedAccurate: true,
            })
          : action === 'archive'
            ? await profileApi.archiveFact(fact.id, { expectedVersion: fact.version })
            : await profileApi.restoreFact(fact.id, { expectedVersion: fact.version });
      upsertFact(saved, setFacts);
      setAttestingFactId(undefined);
      setAttested(false);
      setMessage(
        action === 'confirm'
          ? 'Career fact confirmed.'
          : action === 'archive'
            ? 'Career fact archived.'
            : 'Career fact restored to draft.',
      );
    } catch (error) {
      handleLifecycleError(error, onExpired, setFailure);
    } finally {
      setBusy(false);
    }
  };

  const currentEditFact = facts.find((fact) => fact.id === editingFactId);
  return (
    <section className="profile-workspace" aria-labelledby="profile-title">
      <div className="section-heading">
        <p className="eyebrow">Owner profile</p>
        <h1 id="profile-title">Candidate profile</h1>
        <p>
          Manage the facts and presentation text your future application materials will depend on.
        </p>
      </div>
      <p className="status-line" role="status" aria-live="polite">
        {message}
      </p>
      {failure && (
        <div className="alert" role="alert">
          <p>{failure}</p>
          {conflict && (
            <button onClick={() => void Promise.all([loadProfile(), loadFacts()])}>
              Reload latest version
            </button>
          )}
        </div>
      )}

      {profileMode === 'loading' && <p>Loading profile...</p>}
      {(profileMode === 'empty' || profileMode === 'edit') && (
        <ProfileForm
          mode={profileMode}
          form={profileForm}
          errors={profileErrors}
          busy={busy}
          onChange={setProfileForm}
          onCancel={
            profile
              ? () => {
                  setProfileForm(profileToFields(profile));
                  setProfileMode('view');
                  setProfileErrors({});
                }
              : undefined
          }
          onSubmit={saveProfile}
        />
      )}
      {profileMode === 'empty' && (
        <p className="empty-note">
          Create your profile once, then add structured facts you can confirm before they are used.
        </p>
      )}
      {profileMode === 'view' && profile && (
        <ProfileSummary profile={profile} onEdit={() => setProfileMode('edit')} />
      )}

      <div className="facts-header">
        <div>
          <h2>Career facts</h2>
          <p>
            Confirmed means owner-attested. It is not independent employer, school, certification,
            or application verification.
          </p>
          <p>Only confirmed facts may later be used for generated application content.</p>
        </div>
        <button disabled={busy} onClick={beginCreateFact}>
          Add career fact
        </button>
      </div>
      <fieldset className="filter-bar">
        <legend>Filter career facts</legend>
        <label>
          Category
          <select
            value={categoryFilter}
            onChange={(event) => setCategoryFilter(event.target.value as '' | CareerFactCategory)}
          >
            <option value="">All categories</option>
            {careerFactCategories.map((category) => (
              <option key={category} value={category}>
                {label(category)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Status
          <select
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value as '' | CareerFactStatus)}
          >
            <option value="">All statuses</option>
            {careerFactStatuses.map((status) => (
              <option key={status} value={status}>
                {label(status)}
              </option>
            ))}
          </select>
        </label>
      </fieldset>

      {(factMode === 'create' || currentEditFact) && (
        <CareerFactForm
          title={currentEditFact ? 'Edit career fact' : 'Add career fact'}
          confirmedWarning={currentEditFact?.status === 'CONFIRMED'}
          form={factForm}
          errors={factErrors}
          busy={busy}
          onChange={setFactForm}
          onCancel={() => {
            setFactMode('idle');
            setEditingFactId(undefined);
            setFactErrors({});
          }}
          onSubmit={saveFact}
        />
      )}

      {facts.length === 0 ? (
        <p className="empty-note">
          {categoryFilter || statusFilter
            ? 'No career facts match the selected filters.'
            : 'No career facts yet. Add one as a draft, then confirm it when it is accurate.'}
        </p>
      ) : (
        <ul className="fact-list" aria-label="Career facts">
          {facts.map((fact) => (
            <li key={fact.id} className="fact-card">
              <div className="fact-title">
                <h3>{label(fact.category)}</h3>
                <span className={`status-pill status-${fact.status.toLowerCase()}`}>
                  {label(fact.status)}
                </span>
              </div>
              <p>{fact.factualContent}</p>
              <dl>
                {fact.organization && <Detail name="Organization" value={fact.organization} />}
                {fact.title && <Detail name="Title" value={fact.title} />}
                {fact.location && <Detail name="Location" value={fact.location} />}
                <Detail name="Dates" value={dateRange(fact)} />
                <Detail name="Updated" value={new Date(fact.updatedAt).toLocaleString()} />
              </dl>
              <div className="fact-actions">
                {fact.status !== 'ARCHIVED' && (
                  <button disabled={busy} onClick={() => beginEditFact(fact)}>
                    Edit
                  </button>
                )}
                {fact.status === 'DRAFT' && (
                  <div className="attestation">
                    {attestingFactId === fact.id && (
                      <label>
                        <input
                          type="checkbox"
                          checked={attested}
                          onChange={(event) => setAttested(event.target.checked)}
                        />
                        I confirm that this career fact is accurate and may be used in job matching
                        and application materials.
                      </label>
                    )}
                    {attestingFactId !== fact.id ? (
                      <button
                        disabled={busy}
                        onClick={() => {
                          setAttestingFactId(fact.id);
                          setAttested(false);
                        }}
                      >
                        Confirm as accurate
                      </button>
                    ) : (
                      <button
                        disabled={busy || !attested}
                        onClick={() => void lifecycle(fact, 'confirm')}
                      >
                        Confirm as accurate
                      </button>
                    )}
                  </div>
                )}
                {fact.status !== 'ARCHIVED' && (
                  <button disabled={busy} onClick={() => void lifecycle(fact, 'archive')}>
                    Archive
                  </button>
                )}
                {fact.status === 'ARCHIVED' && (
                  <>
                    <p className="inline-note">Restoration returns this fact to draft.</p>
                    <button disabled={busy} onClick={() => void lifecycle(fact, 'restore')}>
                      Restore to draft
                    </button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ProfileForm({
  mode,
  form,
  errors,
  busy,
  onChange,
  onCancel,
  onSubmit,
}: {
  mode: 'empty' | 'edit';
  form: ProfileFields;
  errors: Record<string, string>;
  busy: boolean;
  onChange: (form: ProfileFields) => void;
  onCancel?: (() => void) | undefined;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="profile-form" onSubmit={(event) => void onSubmit(event)} noValidate>
      <fieldset disabled={busy}>
        <legend>{mode === 'empty' ? 'Create profile' : 'Edit profile'}</legend>
        <TextInput
          label="Professional display name"
          value={form.professionalDisplayName}
          maxLength={profileLimits.professionalDisplayName}
          required
          error={errors.professionalDisplayName}
          onChange={(value) => onChange({ ...form, professionalDisplayName: value })}
        />
        <TextInput
          label="Professional headline"
          value={form.professionalHeadline ?? ''}
          maxLength={profileLimits.professionalHeadline}
          helper="A concise role or professional positioning statement."
          onChange={(value) => onChange({ ...form, professionalHeadline: value })}
        />
        <TextArea
          label="Career summary"
          value={form.careerSummary ?? ''}
          maxLength={profileLimits.careerSummary}
          helper="User-authored presentation text. Keep it consistent with confirmed career facts."
          error={errors.careerSummary}
          onChange={(value) => onChange({ ...form, careerSummary: value })}
        />
        <TextInput
          label="Location preference"
          value={form.locationPreference ?? ''}
          maxLength={profileLimits.locationPreference}
          onChange={(value) => onChange({ ...form, locationPreference: value })}
        />
        <TextArea
          label="Target roles"
          value={form.targetRoles ?? ''}
          maxLength={profileLimits.targetRoles}
          helper="Roles, titles, industries, or teams you want to target."
          onChange={(value) => onChange({ ...form, targetRoles: value })}
        />
        <TextArea
          label="Work authorization statement"
          value={form.workAuthorizationStatement ?? ''}
          maxLength={profileLimits.workAuthorizationStatement}
          helper="Use a plain statement only. Do not enter document numbers."
          onChange={(value) => onChange({ ...form, workAuthorizationStatement: value })}
        />
        <TextArea
          label="Work-location preferences"
          value={form.workLocationPreferences ?? ''}
          maxLength={profileLimits.workLocationPreferences}
          onChange={(value) => onChange({ ...form, workLocationPreferences: value })}
        />
      </fieldset>
      <div className="form-actions">
        <button disabled={busy}>{busy ? 'Saving...' : 'Save profile'}</button>
        {onCancel && (
          <button className="secondary-button" type="button" disabled={busy} onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}

function ProfileSummary({ profile, onEdit }: { profile: CandidateProfile; onEdit: () => void }) {
  const details = useMemo<[string, ReactNode][]>(
    () => [
      ['Headline', profile.professionalHeadline],
      ['Summary', profile.careerSummary],
      ['Location preference', profile.locationPreference],
      ['Target roles', profile.targetRoles],
      ['Work authorization', profile.workAuthorizationStatement],
      ['Work-location preferences', profile.workLocationPreferences],
      ['Last updated', new Date(profile.updatedAt).toLocaleString()],
    ],
    [profile],
  );
  return (
    <article className="profile-summary">
      <div className="fact-title">
        <h2>{profile.professionalDisplayName}</h2>
        <button onClick={onEdit}>Edit profile</button>
      </div>
      <dl>
        {details.map(([name, value]) => (
          <Detail key={name} name={name} value={value || 'Not provided'} />
        ))}
      </dl>
    </article>
  );
}

function CareerFactForm({
  title,
  confirmedWarning,
  form,
  errors,
  busy,
  onChange,
  onCancel,
  onSubmit,
}: {
  title: string;
  confirmedWarning: boolean;
  form: CareerFactFields;
  errors: Record<string, string>;
  busy: boolean;
  onChange: (form: CareerFactFields) => void;
  onCancel: () => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form
      className="profile-form compact-form"
      onSubmit={(event) => void onSubmit(event)}
      noValidate
    >
      <fieldset disabled={busy}>
        <legend>{title}</legend>
        {confirmedWarning && (
          <p className="warning">
            Editing this confirmed fact will return it to draft and it must be confirmed again.
          </p>
        )}
        <label>
          Category
          <select
            required
            value={form.category}
            onChange={(event) =>
              onChange({ ...form, category: event.target.value as CareerFactCategory })
            }
          >
            {careerFactCategories.map((category) => (
              <option key={category} value={category}>
                {label(category)}
              </option>
            ))}
          </select>
        </label>
        <TextArea
          label="Factual content"
          value={form.factualContent}
          maxLength={factLimits.factualContent}
          required
          error={errors.factualContent}
          onChange={(value) => onChange({ ...form, factualContent: value })}
        />
        <TextInput
          label="Organization"
          value={form.organization ?? ''}
          maxLength={factLimits.organization}
          error={errors.organization}
          onChange={(value) => onChange({ ...form, organization: value })}
        />
        <TextInput
          label="Title"
          value={form.title ?? ''}
          maxLength={factLimits.title}
          error={errors.title}
          onChange={(value) => onChange({ ...form, title: value })}
        />
        <TextInput
          label="Location"
          value={form.location ?? ''}
          maxLength={factLimits.location}
          error={errors.location}
          onChange={(value) => onChange({ ...form, location: value })}
        />
        <div className="two-column">
          <TextInput
            label="Start date"
            type="date"
            value={form.startedOn ?? ''}
            error={errors.startedOn}
            onChange={(value) => onChange({ ...form, startedOn: value })}
          />
          <TextInput
            label="End date"
            type="date"
            value={form.endedOn ?? ''}
            error={errors.endedOn}
            onChange={(value) => onChange({ ...form, endedOn: value })}
          />
        </div>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={form.ongoing}
            onChange={(event) => onChange({ ...form, ongoing: event.target.checked })}
          />
          Ongoing
        </label>
      </fieldset>
      <div className="form-actions">
        <button disabled={busy}>{busy ? 'Saving...' : 'Save career fact'}</button>
        <button className="secondary-button" type="button" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function TextInput({
  label,
  value,
  onChange,
  maxLength,
  required,
  helper,
  error,
  type = 'text',
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  maxLength?: number | undefined;
  required?: boolean;
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
  const id = fieldId(props.label);
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

function ManageAccounts({ onBack, onExpired }: { onBack: () => void; onExpired: () => void }) {
  const [accounts, setAccounts] = useState<ManagedAccount[]>([]);
  const [message, setMessage] = useState('Loading household accounts...');
  const [busyId, setBusyId] = useState<string>();
  useEffect(() => {
    void authApi
      .listAccounts()
      .then((result) => {
        setAccounts(result);
        setMessage('');
      })
      .catch((failure) => {
        if (failure instanceof ApiError && failure.status === 401) onExpired();
        else
          setMessage(
            failure instanceof ApiError && failure.status === 403
              ? 'You do not have permission to manage accounts.'
              : 'Household accounts are temporarily unavailable.',
          );
      });
  }, [onExpired]);
  const transition = async (account: ManagedAccount) => {
    const action = account.status === 'ACTIVE' ? 'disable' : 'reactivate';
    if (
      !window.confirm(`${action === 'disable' ? 'Disable' : 'Reactivate'} ${account.displayName}?`)
    )
      return;
    setBusyId(account.accountId);
    setMessage('');
    try {
      if (action === 'disable') await authApi.disableAccount(account.accountId);
      else await authApi.reactivateAccount(account.accountId);
      setAccounts((current) =>
        current.map((item) =>
          item.accountId === account.accountId
            ? { ...item, status: action === 'disable' ? 'DISABLED' : 'ACTIVE' }
            : item,
        ),
      );
      setMessage(
        `${account.displayName} was ${action === 'disable' ? 'disabled' : 'reactivated'}.`,
      );
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onExpired();
      else
        setMessage(
          failure instanceof ApiError && failure.status === 403
            ? 'You do not have permission to manage accounts.'
            : 'The account was not changed. Refresh and try again.',
        );
    } finally {
      setBusyId(undefined);
    }
  };
  return (
    <AuthFrame title="Manage household members">
      <p>Account access only. Private career content remains inaccessible to administrators.</p>
      <p role="status" aria-live="polite">
        {message}
      </p>
      <ul className="account-list" aria-label="Household accounts">
        {accounts.map((account) => (
          <li key={account.accountId} className="account-card">
            <h2>{account.displayName}</h2>
            <dl>
              <Detail name="Login" value={account.loginName} />
              <Detail name="Role" value={account.role} />
              <Detail name="Status" value={<strong>{account.status}</strong>} />
              <Detail name="Created" value={new Date(account.createdAt).toLocaleDateString()} />
            </dl>
            {account.role === 'MEMBER' &&
              (account.status === 'ACTIVE' || account.status === 'DISABLED') && (
                <button
                  disabled={busyId === account.accountId}
                  onClick={() => void transition(account)}
                >
                  {account.status === 'ACTIVE' ? 'Disable member' : 'Reactivate member'}
                </button>
              )}
          </li>
        ))}
      </ul>
      <button className="text-button" onClick={onBack}>
        Back to workspace
      </button>
    </AuthFrame>
  );
}

function CreateInvitation({ onBack, onExpired }: { onBack: () => void; onExpired: () => void }) {
  const [invitation, setInvitation] = useState<{ token: string; expiresAt: string }>();
  const [copied, setCopied] = useState(false);
  useEffect(() => () => setInvitation(undefined), []);
  const link = invitation
    ? `${window.location.origin}/#invite=${encodeURIComponent(invitation.token)}`
    : '';
  const create = async () => {
    try {
      setInvitation(await authApi.createInvitation());
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) onExpired();
    }
  };
  return (
    <AuthFrame title="Create a member invitation">
      <p>The link is displayed once and cannot be recovered later.</p>
      {!invitation && <button onClick={() => void create()}>Create MEMBER invitation</button>}
      {invitation && (
        <div>
          <label>
            One-time invitation link
            <input readOnly value={link} />
          </label>
          <button
            onClick={() => void navigator.clipboard.writeText(link).then(() => setCopied(true))}
          >
            Copy invitation link
          </button>
          <p role="status" aria-live="polite">
            {copied ? 'Invitation link copied.' : `Expires ${invitation.expiresAt}`}
          </p>
        </div>
      )}
      <button className="text-button" onClick={onBack}>
        Back to workspace
      </button>
    </AuthFrame>
  );
}

function AuthFrame({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="auth-page">
      <section className="auth-card">
        <Brand />
        <h1>{title}</h1>
        <p>Private household access with truthful, user-controlled application materials.</p>
        {children}
      </section>
    </main>
  );
}

function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark" aria-hidden="true">
        JA
      </span>
      <span>Job Search Assistant</span>
    </span>
  );
}

function profileToFields(profile: CandidateProfile): ProfileFields {
  return {
    professionalDisplayName: profile.professionalDisplayName,
    professionalHeadline: profile.professionalHeadline ?? '',
    careerSummary: profile.careerSummary ?? '',
    locationPreference: profile.locationPreference ?? '',
    targetRoles: profile.targetRoles ?? '',
    workAuthorizationStatement: profile.workAuthorizationStatement ?? '',
    workLocationPreferences: profile.workLocationPreferences ?? '',
  };
}

function factToFields(fact: CareerFact): CareerFactFields {
  return {
    category: fact.category,
    factualContent: fact.factualContent,
    organization: fact.organization ?? '',
    title: fact.title ?? '',
    location: fact.location ?? '',
    startedOn: fact.startedOn ?? '',
    endedOn: fact.endedOn ?? '',
    ongoing: fact.ongoing,
  };
}

function cleanProfile(form: ProfileFields): ProfileFields {
  return Object.fromEntries(
    Object.entries(form).map(([key, value]) => [key, value?.trim() ?? '']),
  ) as ProfileFields;
}

function cleanFact(form: CareerFactFields): CareerFactFields {
  return {
    category: form.category,
    factualContent: form.factualContent.trim(),
    organization: form.organization?.trim() ?? '',
    title: form.title?.trim() ?? '',
    location: form.location?.trim() ?? '',
    startedOn: form.startedOn || null,
    endedOn: form.endedOn || null,
    ongoing: form.ongoing,
  };
}

function validateProfile(form: ProfileFields): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!form.professionalDisplayName.trim()) {
    errors.professionalDisplayName = 'Professional display name is required.';
  }
  for (const [field, limit] of Object.entries(profileLimits)) {
    const value = String(form[field as keyof ProfileFields] ?? '');
    if (value.length > limit) errors[field] = `Use ${limit} characters or fewer.`;
  }
  return errors;
}

function validateFact(form: CareerFactFields): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!careerFactCategories.includes(form.category)) errors.category = 'Choose a category.';
  if (!form.factualContent.trim()) errors.factualContent = 'Factual content is required.';
  for (const [field, limit] of Object.entries(factLimits)) {
    const value = String(form[field as keyof CareerFactFields] ?? '');
    if (value.length > limit) errors[field] = `Use ${limit} characters or fewer.`;
  }
  if (form.endedOn && !form.startedOn) errors.endedOn = 'End date requires a start date.';
  if (form.startedOn && form.endedOn && form.endedOn < form.startedOn) {
    errors.endedOn = 'End date cannot precede start date.';
  }
  if (form.ongoing && form.endedOn) errors.endedOn = 'Ongoing facts cannot have an end date.';
  return errors;
}

function upsertFact(
  fact: CareerFact,
  setFacts: (updater: (facts: CareerFact[]) => CareerFact[]) => void,
) {
  setFacts((current) => {
    const index = current.findIndex((item) => item.id === fact.id);
    if (index === -1) return [fact, ...current];
    return current.map((item) => (item.id === fact.id ? fact : item));
  });
}

function handleWriteError(
  error: unknown,
  onExpired: () => void,
  setFailure: (message: string) => void,
  setConflict: (conflict: boolean) => void,
) {
  if (error instanceof ApiError && error.status === 401) onExpired();
  else if (error instanceof ApiError && error.status === 409) {
    setConflict(true);
    setFailure(
      'This information changed elsewhere. Review your unsaved values, then reload the latest version.',
    );
  } else
    setFailure(readableError(error, 'The change was not saved. Check the fields and try again.'));
}

function handleLifecycleError(
  error: unknown,
  onExpired: () => void,
  setFailure: (message: string) => void,
) {
  if (error instanceof ApiError && error.status === 401) onExpired();
  else if (error instanceof ApiError && error.status === 409)
    setFailure('This career fact may have changed. Refresh before trying that action again.');
  else setFailure(readableError(error, 'The career-fact action was not completed.'));
}

function readableError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    if (error.status === 400) return 'Some information is invalid. Review the form and try again.';
    if (error.status === 403) return 'You do not have permission to perform that action.';
    if (error.status === 404) return 'That private item was not found. Refresh and try again.';
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

function dateRange(fact: CareerFact) {
  if (!fact.startedOn && !fact.endedOn) return fact.ongoing ? 'Ongoing' : 'Not provided';
  if (fact.ongoing) return `${fact.startedOn ?? 'Unknown'} to ongoing`;
  return `${fact.startedOn ?? 'Unknown'} to ${fact.endedOn ?? 'present'}`;
}

function fieldId(labelText: string) {
  return labelText
    .toLowerCase()
    .replaceAll(/[^a-z0-9]+/g, '-')
    .replaceAll(/^-|-$/g, '');
}
