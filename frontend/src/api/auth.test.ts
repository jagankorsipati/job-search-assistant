import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from './auth';
import { resetCsrf } from './client';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('authApi.login CSRF cache handling', () => {
  beforeEach(() => {
    resetCsrf();
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    resetCsrf();
  });

  it('drops the pre-login CSRF token so the next write fetches a fresh one bound to the rotated session', async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockImplementation((input) => {
      const url = typeof input === 'string' ? input : ((input as Request).url ?? String(input));
      if (url.includes('/api/auth/csrf')) {
        return Promise.resolve(
          jsonResponse({ token: 'token', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' }),
        );
      }
      if (url.includes('/api/auth/login')) {
        return Promise.resolve(
          jsonResponse({ accountId: '11111111-2222-4333-8444-555555555555', role: 'MEMBER' }),
        );
      }
      if (url.includes('/api/auth/logout')) {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      throw new Error(`Unexpected fetch in test: ${url}`);
    });

    await authApi.login('member', 'password');
    await authApi.logout();

    const csrfFetches = fetchMock.mock.calls.filter(([input]) => {
      const url = typeof input === 'string' ? input : ((input as Request).url ?? String(input));
      return url.includes('/api/auth/csrf');
    });
    // One CSRF fetch for the login write itself, and a second, independent
    // fetch for the post-login logout write. A single fetch would mean the
    // pre-login token was (incorrectly) reused after the session rotated.
    expect(csrfFetches).toHaveLength(2);
  });
});
