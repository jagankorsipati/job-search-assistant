import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { tailoringApi, type ProposalFields } from './tailoring';
import { resetCsrf } from './client';
beforeEach(() => {
  resetCsrf();
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockImplementation((url: string) =>
        Promise.resolve(
          new Response(
            JSON.stringify(
              url === '/api/auth/csrf' ? { token: 'csrf', headerName: 'X-CSRF-TOKEN' } : {},
            ),
            { status: 200 },
          ),
        ),
      ),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  resetCsrf();
});
it('projects CRUD payloads, uses CSRF/no-store, and keeps private text and tokens out of URLs and browser storage', async () => {
  const input = {
    targetSection: 'SUMMARY',
    targetReference: 'Private target',
    originalText: null,
    proposedText: 'Private wording',
    evidence: [{ careerFactId: 'f1', userNote: 'Private note' }],
    ownerAccountId: 'forbidden',
    storageKey: 'forbidden',
  } as ProposalFields;
  const store = vi.spyOn(Storage.prototype, 'setItem');
  await tailoringApi.create(input, {
    documentId: 'r1',
    version: 3,
    sha256Checksum: 'a'.repeat(64),
  });
  await tailoringApi.update('p1', input, 7);
  await tailoringApi.review('p1');
  await tailoringApi.approve('p1', 7, 'exact-review-token');
  await tailoringApi.reject('p1', 7);
  await tailoringApi.remove('p1', 7);
  for (const [url, options] of vi.mocked(fetch).mock.calls) {
    expect(String(url)).not.toMatch(/Private|exact-review-token|forbidden/);
    if (String(url) === '/api/auth/csrf') continue;
    expect(options?.cache).toBe('no-store');
    expect(String(options?.body ?? '')).not.toContain('forbidden');
    if (options?.method) expect(options.headers).toHaveProperty('X-CSRF-TOKEN', 'csrf');
  }
  const approve = vi.mocked(fetch).mock.calls.find(([url]) => String(url).endsWith('/approve'));
  expect(JSON.parse(String(approve?.[1]?.body))).toEqual({
    expectedVersion: 7,
    reviewedRevision: 'exact-review-token',
    attestedExperienceAccurate: true,
  });
  expect(store).not.toHaveBeenCalled();
  store.mockRestore();
});
