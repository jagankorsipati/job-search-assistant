import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { apiPostDownload, resetCsrf } from './client';
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
beforeEach(() => {
  resetCsrf();
  vi.stubGlobal('fetch', vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
  resetCsrf();
});
function csrf() {
  return new Response(JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }), {
    status: 200,
  });
}
it('uses CSRF, no-store, exact body tokens and accepts DOCX bytes only', async () => {
  vi.mocked(fetch)
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(
      new Response('synthetic', { status: 200, headers: { 'Content-Type': DOCX } }),
    );
  const blob = await apiPostDownload('/api/documents/proposal/export', {
    expectedVersion: 1,
    resolvedRevision: 'private-token',
  });
  expect(blob.size).toBe(9);
  expect(fetch).toHaveBeenLastCalledWith(
    '/api/documents/proposal/export',
    expect.objectContaining({
      method: 'POST',
      cache: 'no-store',
      body: '{"expectedVersion":1,"resolvedRevision":"private-token"}',
      headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'csrf' }),
    }),
  );
});
it.each([
  new Response('{"code":"export_unapproved"}', { status: 409 }),
  new Response('not docx', { status: 200 }),
  new Response('', { status: 200, headers: { 'Content-Type': DOCX } }),
])('refuses error or malformed downloads', async (response) => {
  vi.mocked(fetch).mockResolvedValueOnce(csrf()).mockResolvedValueOnce(response);
  await expect(apiPostDownload('/api/documents/proposal/export', {})).rejects.toThrow(
    'Request failed',
  );
});
it('clears CSRF on session expiry', async () => {
  vi.mocked(fetch)
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(new Response('{}', { status: 401 }))
    .mockResolvedValueOnce(csrf())
    .mockResolvedValueOnce(
      new Response('synthetic', { status: 200, headers: { 'Content-Type': DOCX } }),
    );
  await expect(apiPostDownload('/api/documents/proposal/export', {})).rejects.toThrow();
  await apiPostDownload('/api/documents/proposal/export', {});
  expect(vi.mocked(fetch).mock.calls.filter(([url]) => url === '/api/auth/csrf')).toHaveLength(2);
});
