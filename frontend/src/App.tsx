import { type FormEvent, type ReactNode, useEffect, useState } from 'react';
import { ApiError, authApi, type Identity } from './api/auth';

type View = 'loading' | 'login' | 'shell' | 'invite' | 'admin';
const navigationItems = ['Dashboard', 'Profile', 'Jobs', 'Documents', 'Applications'] as const;

export function App() {
  const [view, setView] = useState<View>('loading');
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
          window.location.pathname === '/admin/invitations' && me.role === 'ADMIN'
            ? 'admin'
            : 'shell',
        );
      })
      .catch(() => setView(window.location.pathname === '/invite' ? 'invite' : 'login'));
  }, []);
  const navigate = (next: View, path: string) => {
    window.history.pushState(null, '', path);
    setView(next);
  };
  if (view === 'loading')
    return (
      <main className="auth-page" aria-live="polite">
        <p>Restoring your session…</p>
      </main>
    );
  if (view === 'login')
    return (
      <Login
        onSuccess={(me) => {
          setIdentity(me);
          navigate('shell', '/');
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
  if (view === 'admin' && identity?.role === 'ADMIN')
    return (
      <CreateInvitation
        onBack={() => navigate('shell', '/')}
        onExpired={() => {
          setIdentity(undefined);
          navigate('login', '/login');
        }}
      />
    );
  return (
    <Shell
      identity={identity!}
      onAdmin={() => navigate('admin', '/admin/invitations')}
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
        <button disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
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
        <p>Use 15–128 characters. Long, unique passphrases are encouraged.</p>
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
        <button disabled={busy}>{busy ? 'Creating account…' : 'Create account'}</button>
      </form>
    </AuthFrame>
  );
}

function Shell({
  identity,
  onAdmin,
  onLogout,
}: {
  identity: Identity;
  onAdmin: () => void;
  onLogout: () => void;
}) {
  return (
    <div className="page-shell">
      <header className="site-header">
        <Brand />
        <p>Signed in as {identity.role.toLowerCase()}</p>
        <button onClick={onLogout}>Sign out</button>
      </header>
      <div className="workspace-layout">
        <aside className="sidebar">
          <nav aria-label="Primary navigation">
            <ul className="nav-list">
              {navigationItems.map((item) => (
                <li key={item}>
                  <span className="nav-item" aria-disabled="true">
                    {item}
                    <span className="upcoming-label">Upcoming</span>
                  </span>
                </li>
              ))}
            </ul>
          </nav>
          {identity.role === 'ADMIN' && <button onClick={onAdmin}>Create member invitation</button>}
        </aside>
        <main id="main-content" className="main-content">
          <section className="hero">
            <p className="eyebrow">Private by default · Truth before optimization</p>
            <h1>Your job-search workspace.</h1>
            <p className="hero-copy">
              Product sections remain upcoming while identity and isolation are completed carefully.
            </p>
          </section>
        </main>
      </div>
    </div>
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
