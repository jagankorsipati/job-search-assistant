import { useCallback, useEffect, useState } from 'react';

import { checkBackendHealth } from './api/health';

type ConnectionState = 'loading' | 'connected' | 'unavailable';

const navigationItems = ['Dashboard', 'Profile', 'Jobs', 'Documents', 'Applications'] as const;

export function App() {
  const [connectionState, setConnectionState] = useState<ConnectionState>('loading');

  const refreshHealth = useCallback(async (signal?: AbortSignal) => {
    setConnectionState('loading');

    try {
      await checkBackendHealth(signal);
      setConnectionState('connected');
    } catch (error: unknown) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return;
      }
      setConnectionState('unavailable');
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void checkBackendHealth(controller.signal)
      .then(() => setConnectionState('connected'))
      .catch((error: unknown) => {
        if (!isAbortError(error)) {
          setConnectionState('unavailable');
        }
      });

    return () => controller.abort();
  }, []);

  return (
    <>
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>

      <div className="page-shell">
        <header className="site-header">
          <a className="brand" href="/" aria-label="Job Search Assistant home">
            <span className="brand-mark" aria-hidden="true">
              JA
            </span>
            <span>Job Search Assistant</span>
          </a>

          <ConnectionStatus state={connectionState} onRetry={() => void refreshHealth()} />
        </header>

        <div className="workspace-layout">
          <aside className="sidebar" aria-label="Product navigation">
            <p className="nav-eyebrow">Workspace</p>
            <nav aria-label="Primary navigation">
              <ul className="nav-list">
                {navigationItems.map((item) => (
                  <li key={item}>
                    <span className="nav-item" aria-disabled="true">
                      <span>{item}</span>
                      <span className="upcoming-label">Upcoming</span>
                    </span>
                  </li>
                ))}
              </ul>
            </nav>
            <p className="nav-note">Features will arrive in reviewed, testable phases.</p>
          </aside>

          <main id="main-content" className="main-content">
            <section className="hero" aria-labelledby="hero-title">
              <p className="eyebrow">Private by default · Truth before optimization</p>
              <h1 id="hero-title">A calmer place to manage the work behind your job search.</h1>
              <p className="hero-copy">
                Keep opportunities, evidence, and application materials organized in one private
                household workspace—without turning job requirements into claims about you.
              </p>
            </section>

            <section className="principles" aria-labelledby="principles-title">
              <div>
                <p className="section-number">01</p>
                <h2 id="principles-title">Your experience stays yours.</h2>
              </div>
              <div className="principle-grid">
                <article>
                  <h3>Private workspace</h3>
                  <p>
                    Career records and documents are designed to remain isolated to each household
                    member.
                  </p>
                </article>
                <article>
                  <h3>Evidence-backed writing</h3>
                  <p>
                    Future document tools may reword verified experience, but they must never invent
                    skills, achievements, or metrics.
                  </p>
                </article>
                <article>
                  <h3>You stay in control</h3>
                  <p>
                    Suggestions and exports will require review. Automated application submission is
                    not part of this product.
                  </p>
                </article>
              </div>
            </section>
          </main>
        </div>

        <footer className="site-footer">
          <p>Built for careful, user-controlled job searching.</p>
          <p>Phase 1C · Frontend foundation</p>
        </footer>
      </div>
    </>
  );
}

interface ConnectionStatusProps {
  state: ConnectionState;
  onRetry: () => void;
}

function ConnectionStatus({ state, onRetry }: ConnectionStatusProps) {
  if (state === 'loading') {
    return (
      <div className="connection-status" role="status" aria-live="polite">
        <span className="status-dot status-dot-loading" aria-hidden="true" />
        Checking backend…
      </div>
    );
  }

  if (state === 'connected') {
    return (
      <div className="connection-status" role="status" aria-live="polite">
        <span className="status-dot status-dot-connected" aria-hidden="true" />
        Backend connected
      </div>
    );
  }

  return (
    <div className="connection-status connection-status-unavailable" role="alert">
      <span className="status-dot status-dot-unavailable" aria-hidden="true" />
      <span>Backend unavailable</span>
      <button className="retry-button" type="button" onClick={onRetry}>
        Retry
      </button>
    </div>
  );
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
