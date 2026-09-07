import { expect, test, type Browser, type Page } from '@playwright/test';

const adminLogin = process.env.E2E_ADMIN_LOGIN ?? 'e2e.admin';
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
const memberPassword = process.env.E2E_MEMBER_PASSWORD;

if (!adminPassword || !memberPassword) {
  throw new Error('The E2E runner must provide process-scoped test credentials.');
}

const runSuffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
const memberLogin = `jobs.member.${runSuffix}`;
const otherLogin = `jobs.other.${runSuffix}`;

interface CapturedJob {
  id: string;
  companyName: string;
  jobTitle: string;
  workLocation?: string | null;
  postingUrl?: string | null;
  sourceType: string;
  externalPostingId?: string | null;
  version: number;
  archived: boolean;
}

interface Snapshot {
  id: string;
  sequence: number;
  descriptionText: string;
}

interface CaptureJobResponse {
  job: CapturedJob;
  initialSnapshot?: Snapshot | null;
}

interface Application {
  id: string;
  jobId: string;
  status: string;
  appliedAt?: string | null;
  nextActionText?: string | null;
  version: number;
  archived: boolean;
}

interface HistoryEvent {
  id: string;
  previousStatus?: string | null;
  newStatus: string;
  note?: string | null;
}

async function login(page: Page, loginName: string, password: string) {
  await page.goto('/login');
  await expect(
    page.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  await page.getByLabel('Login name').fill(loginName);
  await page.getByLabel('Password').fill(password);
  const loginResponsePromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/auth/login' &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Sign in' }).click();
  const loginResponse = await loginResponsePromise;
  const status = loginResponse.status();
  let genericCode = 'none';
  try {
    const body = (await loginResponse.json()) as { code?: unknown };
    if (typeof body.code === 'string' && /^[a-z0-9_-]{1,64}$/.test(body.code)) {
      genericCode = body.code;
    }
  } catch {
    // A successful response need not provide a generic error code.
  }
  // 401 invalid credentials, 403 CSRF/session rejection, 409 unexpected conflict,
  // 429 rate limited, 500 server error. Only the safe status and generic code are logged.
  const safeResponseSummary = `Login response: HTTP ${status}, generic code ${genericCode}`;
  console.info(safeResponseSummary);
  expect(status, safeResponseSummary).toBe(200);
  await expect(page.getByRole('heading', { name: 'Your job-search workspace.' })).toBeVisible();
  await page.goto('/');
}

async function logout(page: Page) {
  await page.evaluate(async () => {
    await fetch('/api/auth/logout', { method: 'POST' });
  });
}

/**
 * Logs the shared administrator in once. The caller is expected to reuse the
 * returned page for every invitation it needs, instead of logging the fixed
 * administrator account in again for each member, which would needlessly
 * consume the production login-rate-limit budget.
 */
async function loginAdmin(browser: Browser): Promise<Page> {
  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await login(adminPage, adminLogin, adminPassword);
  return adminPage;
}

async function inviteMember(
  adminPage: Page,
  browser: Browser,
  baseURL: string | undefined,
  loginName: string,
  displayName: string,
) {
  await adminPage.getByRole('button', { name: 'Create member invitation' }).click();
  await adminPage.getByRole('button', { name: 'Create MEMBER invitation' }).click();
  const invitationLink = await adminPage.getByLabel('One-time invitation link').inputValue();
  expect(invitationLink.startsWith(`${baseURL}/#invite=`)).toBeTruthy();
  await adminPage.getByRole('button', { name: 'Back to workspace' }).click();

  const memberContext = await browser.newContext();
  const memberPage = await memberContext.newPage();
  await memberPage.goto(invitationLink);
  await expect(memberPage).toHaveURL(`${baseURL}/invite`);
  await memberPage.getByLabel('Display name').fill(displayName);
  await memberPage.getByLabel('Login name').fill(loginName);
  await memberPage.getByLabel('Password', { exact: true }).fill(memberPassword);
  await memberPage.getByLabel('Confirm password').fill(memberPassword);
  await memberPage.getByRole('button', { name: 'Create account' }).click();
  await expect(
    memberPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  await memberContext.close();
}

async function csrf(page: Page) {
  return page.evaluate(async () => {
    const response = await fetch('/api/auth/csrf', { headers: { Accept: 'application/json' } });
    const token = (await response.json()) as { token: string; headerName: string };
    return { token: token.token, headerName: token.headerName };
  });
}

async function apiJson<T>(page: Page, path: string, init?: RequestInit): Promise<T> {
  return page.evaluate(
    async ({ path, init }) => {
      const response = await fetch(path, init);
      if (!response.ok) throw new Error(`Unexpected safe HTTP status ${response.status}`);
      return (await response.json()) as unknown;
    },
    { path, init },
  ) as Promise<T>;
}

async function apiStatusAndShape(page: Page, path: string, init?: RequestInit) {
  return page.evaluate(
    async ({ path, init }) => {
      const response = await fetch(path, init);
      let code: unknown;
      let keys: string[] = [];
      try {
        const body = (await response.json()) as Record<string, unknown>;
        code = body.code;
        keys = Object.keys(body).sort();
      } catch {
        // Some rejected responses intentionally have no useful body.
      }
      return {
        status: response.status,
        code: typeof code === 'string' ? code : undefined,
        keys,
      };
    },
    { path, init },
  );
}

async function apiWrite<T>(page: Page, path: string, body: unknown, method = 'POST') {
  const token = await csrf(page);
  return apiJson<T>(page, path, {
    method,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [token.headerName]: token.token,
    },
    body: JSON.stringify(body),
  });
}

async function captureJob(page: Page, body: Record<string, unknown>) {
  return apiWrite<CaptureJobResponse>(page, '/api/jobs', body);
}

async function openJobs(page: Page) {
  await page.goto('/jobs');
  await expect(page.getByRole('heading', { name: 'Jobs' })).toBeVisible();
}

async function openApplications(page: Page) {
  await page.goto('/applications');
  await expect(page.getByRole('heading', { name: 'Applications' })).toBeVisible();
}

async function assertNoBrowserPersistence(page: Page, forbidden: string[]) {
  const result = await page.evaluate(async (forbiddenValues) => {
    const databases =
      'databases' in indexedDB
        ? await indexedDB.databases().then((items) => items.map((item) => item.name ?? ''))
        : [];
    const url = window.location.href;
    const cookie = document.cookie;
    const html = document.documentElement.innerHTML;
    return {
      local: Object.keys(localStorage).length,
      session: Object.keys(sessionStorage).length,
      databases,
      urlHasForbidden: forbiddenValues.some((value) => value !== '' && url.includes(value)),
      cookieHasForbidden: forbiddenValues.some((value) => value !== '' && cookie.includes(value)),
      unsafeError: /SQLException|stack trace|owner_account_id|JSA_SESSION|csrf/i.test(html),
    };
  }, forbidden);
  expect(result.local).toBe(0);
  expect(result.session).toBe(0);
  expect(result.databases.filter((name) => /job|application|search/i.test(name))).toEqual([]);
  expect(result.urlHasForbidden).toBeFalsy();
  expect(result.cookieHasForbidden).toBeFalsy();
  expect(result.unsafeError).toBeFalsy();
}

test('real browser job and application lifecycle, isolation, conflicts, csrf, and privacy', async ({
  browser,
  baseURL,
}) => {
  test.setTimeout(300_000);
  const adminPage = await loginAdmin(browser);
  await inviteMember(adminPage, browser, baseURL, memberLogin, 'Job Security Member');
  await inviteMember(adminPage, browser, baseURL, otherLogin, 'Other Job Member');

  const anonymousPage = await (await browser.newContext()).newPage();
  await anonymousPage.goto('/jobs');
  await expect(
    anonymousPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect((await apiStatusAndShape(anonymousPage, '/api/jobs')).status).toBe(401);
  await anonymousPage.goto('/applications');
  await expect(
    anonymousPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect((await apiStatusAndShape(anonymousPage, '/api/applications')).status).toBe(401);

  const memberContext = await browser.newContext();
  const memberPage = await memberContext.newPage();
  const postingRequests: string[] = [];
  memberPage.on('request', (request) => {
    if (request.url().includes('phase4e-posting.example')) postingRequests.push('posting');
  });
  await login(memberPage, memberLogin, memberPassword);

  await openJobs(memberPage);
  await expect(memberPage.getByText('No active jobs yet.')).toBeVisible();
  await memberPage.getByRole('button', { name: 'Capture job' }).click();
  await memberPage.getByLabel('Company name').fill('Phase4E Manual Company');
  await memberPage.getByLabel('Job title').fill('Manual Verification Role');
  await memberPage.getByLabel('Work location').fill('Remote');
  await memberPage.getByRole('button', { name: 'Capture job' }).last().click();
  await expect(memberPage.getByText('Job captured.')).toBeVisible();
  await expect(memberPage.getByRole('heading', { name: 'Phase4E Manual Company' })).toBeVisible();

  await memberPage.getByRole('button', { name: 'Capture job' }).click();
  await memberPage.getByLabel('Company name').fill('Phase4E Snapshot Company');
  await memberPage.getByLabel('Job title').fill('Snapshot Verification Role');
  await memberPage.getByLabel('Source type').first().selectOption('PASTED_DESCRIPTION');
  await memberPage.getByLabel('Employment type').first().selectOption('FULL_TIME');
  await memberPage.getByLabel('External posting ID').fill('P4E-SNAPSHOT');
  await memberPage.getByLabel('Description text').first().fill('Original immutable browser text.');
  await memberPage.getByRole('button', { name: 'Capture job' }).last().click();
  await expect(memberPage.getByText('Job captured with initial snapshot.')).toBeVisible();
  expect(await memberPage.locator('.timeline').textContent()).toContain(
    'Original immutable browser text.',
  );

  await memberPage.getByRole('button', { name: 'Capture job' }).click();
  await memberPage.getByLabel('Company name').fill('Phase4E URL Company');
  await memberPage.getByLabel('Job title').fill('URL Verification Role');
  await memberPage.getByLabel('Source type').first().selectOption('URL_REFERENCE');
  await memberPage.getByRole('button', { name: 'Capture job' }).last().click();
  await expect(memberPage.getByText('URL reference requires a posting URL.')).toBeVisible();
  await memberPage.getByLabel('Posting URL').fill('https://phase4e-posting.example/jobs/1#apply');
  await memberPage.getByRole('button', { name: 'Capture job' }).last().click();
  await expect(memberPage.getByText('Job captured.')).toBeVisible();
  expect(postingRequests).toHaveLength(0);

  await memberPage.reload();
  await expect(memberPage.getByRole('heading', { name: 'Jobs' })).toBeVisible();
  await expect(memberPage.getByRole('button', { name: /Phase4E URL Company/ })).toBeVisible();
  await memberPage.getByRole('button', { name: /Phase4E Snapshot Company/ }).click();
  await expect(memberPage.getByRole('heading', { name: 'Phase4E Snapshot Company' })).toBeVisible();
  await memberPage.getByRole('button', { name: 'Edit metadata' }).click();
  await memberPage
    .getByRole('form', { name: 'Edit metadata' })
    .getByLabel('Company name')
    .fill('Unsaved Browser Company');
  await memberPage.getByRole('button', { name: 'Cancel' }).click();
  await expect(memberPage.getByRole('heading', { name: 'Phase4E Snapshot Company' })).toBeVisible();
  await memberPage.getByRole('button', { name: 'Edit metadata' }).click();
  await memberPage
    .getByRole('form', { name: 'Edit metadata' })
    .getByLabel('Company name')
    .fill('Phase4E Snapshot Company Updated');
  await memberPage.getByRole('button', { name: 'Edit metadata' }).last().click();
  await expect(memberPage.getByText('Job metadata saved.')).toBeVisible();

  await memberPage.getByLabel('Description text').last().fill('Second immutable browser text.');
  await memberPage.getByRole('button', { name: 'Append snapshot' }).last().click();
  await expect(memberPage.getByText('Immutable snapshot appended.')).toBeVisible();
  expect(await memberPage.locator('.timeline').textContent()).toContain(
    'Original immutable browser text.',
  );
  await memberPage.getByLabel('Description text').last().fill(' Second immutable browser text. ');
  await memberPage.getByRole('button', { name: 'Append snapshot' }).last().click();
  await expect(memberPage.getByRole('alert')).toContainText(
    'That description matches the latest snapshot.',
  );

  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Archive', exact: true }).click();
  await expect(memberPage.getByText('Job archived.')).toBeVisible();
  await memberPage.getByRole('button', { name: 'Archived jobs' }).click();
  await expect(
    memberPage.getByRole('button', { name: /Phase4E Snapshot Company Updated/ }),
  ).toBeVisible();
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Restore', exact: true }).click();
  await expect(memberPage.getByText('Job restored.')).toBeVisible();
  await memberPage.getByRole('button', { name: 'Active jobs' }).click();
  await memberPage.getByLabel('Search loaded jobs').fill('url verification');
  await expect(memberPage.getByText(/Showing 1 of/)).toBeVisible();
  await memberPage.locator('.filter-bar select').first().selectOption('PASTED_DESCRIPTION');
  await expect(memberPage.getByText('No loaded jobs match these filters.')).toBeVisible();
  await memberPage.getByRole('button', { name: 'Clear filters' }).click();
  await memberPage.getByRole('button', { name: 'Capture job' }).click();
  await memberPage.getByLabel('Company name').fill('Phase4E URL Company');
  await memberPage.getByLabel('Job title').fill('Similar URL Role');
  await memberPage.getByLabel('Source type').first().selectOption('URL_REFERENCE');
  await memberPage.getByLabel('Posting URL').fill('https://phase4e-posting.example/jobs/1');
  await memberPage.getByRole('button', { name: 'Capture job' }).last().click();
  await expect(memberPage.getByRole('alert')).toContainText('This looks similar');
  await memberPage.getByRole('button', { name: 'Continue capturing' }).click();
  await expect(memberPage.getByText('Job captured.')).toBeVisible();

  const memberJobs = await apiJson<CapturedJob[]>(memberPage, '/api/jobs?limit=100');
  const urlJob = memberJobs.find((job) => job.companyName === 'Phase4E URL Company');
  const manualJob = memberJobs.find((job) => job.companyName === 'Phase4E Manual Company');
  const snapshotJob = memberJobs.find(
    (job) => job.companyName === 'Phase4E Snapshot Company Updated',
  );
  expect(Boolean(urlJob)).toBeTruthy();
  expect(Boolean(manualJob)).toBeTruthy();
  expect(Boolean(snapshotJob)).toBeTruthy();

  await openApplications(memberPage);
  await memberPage.getByRole('button', { name: 'Create application' }).click();
  await memberPage.getByLabel('Captured job').selectOption(urlJob!.id);
  await memberPage.getByLabel('Private notes').fill('Synthetic private application note.');
  await memberPage.locator('#application-next-action').fill('Synthetic next action');
  await memberPage.locator('#application-next-action-due-date').fill('2026-09-01');
  await memberPage.getByRole('button', { name: 'Create draft application' }).click();
  await expect(
    memberPage.getByText('Draft application created. It has not been submitted.'),
  ).toBeVisible();
  await expect(memberPage.locator('.detail-panel dl')).toContainText('Draft');
  await expect(memberPage.locator('.detail-panel dl')).toContainText('Not recorded');
  await memberPage.getByRole('button', { name: 'Edit notes and next action' }).click();
  await memberPage
    .getByRole('form', { name: 'Notes and next action' })
    .getByLabel('Private notes')
    .fill('Synthetic updated private note.');
  await memberPage.getByRole('button', { name: 'Save application notes' }).click();
  await expect(memberPage.getByText('Notes and next action saved.')).toBeVisible();
  await memberPage.getByLabel('Next status').selectOption('READY_TO_APPLY');
  await memberPage.getByRole('button', { name: 'Record status' }).click();
  await expect(memberPage.getByText('Status recorded from your explicit action.')).toBeVisible();
  await memberPage.getByLabel('Next status').selectOption('APPLIED');
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Record status' }).click();
  await expect(memberPage.locator('.detail-panel dl')).toContainText('Applied');
  await memberPage.getByLabel('Next status').selectOption('INTERVIEWING');
  await memberPage.getByRole('button', { name: 'Record status' }).click();
  await expect(memberPage.locator('.detail-panel dl')).toContainText('Interviewing');
  await memberPage.getByLabel('Next status').selectOption('INTERVIEWING');
  await memberPage.getByRole('button', { name: 'Record status' }).click();
  await expect(memberPage.getByText('Record a meaningful note')).toBeVisible();
  await memberPage.getByLabel('History note').fill('Synthetic second interview stage.');
  await memberPage.getByRole('button', { name: 'Record status' }).click();
  await expect(memberPage.getByText('Synthetic second interview stage.')).toBeVisible();
  await memberPage.getByLabel('Next status').selectOption('OFFER');
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Record status' }).click();
  await memberPage.getByLabel('Next status').selectOption('ACCEPTED');
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Record status' }).click();
  await expect(memberPage.locator('.detail-panel dl')).toContainText('Accepted');
  await expect(memberPage.getByText('Status transition')).toHaveCount(0);
  await expect(memberPage.locator('.detail-panel dl')).toContainText('None');
  const historyText = await memberPage.locator('.timeline').innerText();
  expect(historyText.indexOf('Application created')).toBeLessThan(
    historyText.indexOf('Draft to Ready To Apply'),
  );
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Archive', exact: true }).click();
  await expect(memberPage.getByText('Application archived.')).toBeVisible();
  await memberPage.getByRole('button', { name: 'Archived applications' }).click();
  await expect(memberPage.getByRole('button', { name: /Accepted/ })).toBeVisible();
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Restore', exact: true }).click();
  await expect(memberPage.getByText('Application restored.')).toBeVisible();
  await memberPage.getByRole('button', { name: 'Active applications' }).click();
  await memberPage.getByLabel('Search loaded applications').fill('url company');
  await expect(memberPage.getByText(/Showing 1 of/)).toBeVisible();
  // Scoped to the filter control: a bare getByLabel('Status') also matches the
  // accessible name of the "Status history" landmark region rendered inside
  // the detail panel, which is a strict-mode violation once an application
  // with recorded history is on screen.
  await memberPage.locator('.filter-control select').selectOption('REJECTED');
  await expect(
    memberPage.getByText(/No applications match this filter|No loaded applications/),
  ).toBeVisible();
  await memberPage.getByRole('button', { name: 'Clear filters' }).click();

  const memberApplications = await apiJson<Application[]>(
    memberPage,
    '/api/applications?limit=100',
  );
  const memberApplication = memberApplications.find(
    (application) => application.jobId === urlJob!.id,
  );
  expect(Boolean(memberApplication)).toBeTruthy();
  const memberSnapshots = await apiJson<Snapshot[]>(
    memberPage,
    `/api/jobs/${snapshotJob!.id}/snapshots?limit=50`,
  );
  expect(memberSnapshots.length).toBeGreaterThan(0);
  const memberHistory = await apiJson<HistoryEvent[]>(
    memberPage,
    `/api/applications/${memberApplication!.id}/history?limit=100`,
  );
  expect(memberHistory.some((event) => event.newStatus === 'ACCEPTED')).toBeTruthy();

  const otherContext = await browser.newContext();
  const otherPage = await otherContext.newPage();
  await login(otherPage, otherLogin, memberPassword);
  const otherJob = await captureJob(otherPage, {
    companyName: 'Phase4E Other Company',
    jobTitle: 'Other Verification Role',
    sourceType: 'PASTED_DESCRIPTION',
    descriptionText: 'Other owner immutable text.',
  });
  const otherApplication = await apiWrite<Application>(otherPage, '/api/applications', {
    jobId: otherJob.job.id,
    privateNotes: 'Other owner note.',
  });
  expect((await apiJson<CapturedJob[]>(otherPage, '/api/jobs?limit=100')).length).toBe(1);
  expect((await apiJson<Application[]>(otherPage, '/api/applications?limit=100')).length).toBe(1);
  await openJobs(otherPage);
  await expect(otherPage.getByText('Phase4E URL Company')).toHaveCount(0);
  await openApplications(otherPage);
  await expect(otherPage.getByText('Phase4E URL Company')).toHaveCount(0);

  // The administrator session opened in loginAdmin is reused here rather
  // than logging the shared administrator account in again.
  await openJobs(adminPage);
  await expect(adminPage.getByText('Phase4E URL Company')).toHaveCount(0);
  await openApplications(adminPage);
  await expect(adminPage.getByText('Phase4E URL Company')).toHaveCount(0);

  const missingJobShape = await apiStatusAndShape(
    memberPage,
    '/api/jobs/11111111-2222-4333-8444-555555555555',
  );
  expect(await apiStatusAndShape(adminPage, `/api/jobs/${urlJob!.id}`)).toEqual(missingJobShape);
  const adminCsrf = await csrf(adminPage);
  const adminWriteHeaders = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    [adminCsrf.headerName]: adminCsrf.token,
  };
  for (const [path, init] of [
    [
      `/api/jobs/${urlJob!.id}`,
      {
        method: 'PUT',
        body: {
          ...urlJob,
          accountId: '11111111-2222-4333-8444-555555555555',
          ownerAccountId: '11111111-2222-4333-8444-555555555555',
          expectedVersion: urlJob!.version,
        },
      },
    ],
    [
      `/api/jobs/${urlJob!.id}/archive`,
      { method: 'POST', body: { expectedVersion: urlJob!.version } },
    ],
    [
      `/api/jobs/${urlJob!.id}/restore`,
      { method: 'POST', body: { expectedVersion: urlJob!.version } },
    ],
    [`/api/jobs/${snapshotJob!.id}/snapshots`, { method: 'GET' }],
    [`/api/jobs/${snapshotJob!.id}/snapshots/${memberSnapshots[0]!.id}`, { method: 'GET' }],
    [
      `/api/jobs/${snapshotJob!.id}/snapshots`,
      {
        method: 'POST',
        body: { sourceType: 'PASTED_DESCRIPTION', descriptionText: 'Cross owner' },
      },
    ],
    [`/api/applications/${memberApplication!.id}`, { method: 'GET' }],
    [
      `/api/applications/${memberApplication!.id}`,
      {
        method: 'PUT',
        body: { expectedVersion: memberApplication!.version, privateNotes: 'Cross owner' },
      },
    ],
    [
      `/api/applications/${memberApplication!.id}/transitions`,
      {
        method: 'POST',
        body: { expectedVersion: memberApplication!.version, targetStatus: 'WITHDRAWN' },
      },
    ],
    [`/api/applications/${memberApplication!.id}/history`, { method: 'GET' }],
    [
      `/api/applications/${memberApplication!.id}/archive`,
      { method: 'POST', body: { expectedVersion: memberApplication!.version } },
    ],
    [
      `/api/applications/${memberApplication!.id}/restore`,
      { method: 'POST', body: { expectedVersion: memberApplication!.version } },
    ],
  ] as const) {
    const status = await apiStatusAndShape(adminPage, path, {
      method: init.method,
      headers: init.method === 'GET' ? { Accept: 'application/json' } : adminWriteHeaders,
      body: 'body' in init ? JSON.stringify(init.body) : undefined,
    });
    expect(status.status).toBe(404);
  }
  const missingApplicationShape = await apiStatusAndShape(
    memberPage,
    '/api/applications/11111111-2222-4333-8444-555555555555',
  );
  expect(await apiStatusAndShape(adminPage, `/api/applications/${memberApplication!.id}`)).toEqual(
    missingApplicationShape,
  );
  const memberJobsAfterCrossWrites = await apiJson<CapturedJob[]>(
    memberPage,
    '/api/jobs?limit=100',
  );
  expect(memberJobsAfterCrossWrites.some((job) => job.companyName === 'Cross owner')).toBeFalsy();
  const memberHistoryAfterCrossWrites = await apiJson<HistoryEvent[]>(
    memberPage,
    `/api/applications/${memberApplication!.id}/history?limit=100`,
  );
  expect(memberHistoryAfterCrossWrites.length).toBe(memberHistory.length);

  expect(
    (
      await apiStatusAndShape(memberPage, '/api/jobs', {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify({ companyName: 'No CSRF', jobTitle: 'Role', sourceType: 'MANUAL' }),
      })
    ).status,
  ).toBe(403);
  expect(
    (
      await apiStatusAndShape(memberPage, '/api/applications', {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify({ jobId: manualJob!.id }),
      })
    ).status,
  ).toBe(403);

  const conflictContext = await browser.newContext();
  const conflictPage = await conflictContext.newPage();
  await login(conflictPage, memberLogin, memberPassword);
  await openJobs(conflictPage);
  await memberPage.goto('/jobs');
  await memberPage.getByRole('button', { name: /Phase4E Manual Company/ }).click();
  await conflictPage.getByRole('button', { name: /Phase4E Manual Company/ }).click();
  await memberPage.getByRole('button', { name: 'Edit metadata' }).click();
  await memberPage
    .getByRole('form', { name: 'Edit metadata' })
    .getByLabel('Company name')
    .fill('Phase4E Manual Company Saved');
  await memberPage.getByRole('button', { name: 'Edit metadata' }).last().click();
  await expect(memberPage.getByText('Job metadata saved.')).toBeVisible();
  await conflictPage.getByRole('button', { name: 'Edit metadata' }).click();
  await conflictPage
    .getByRole('form', { name: 'Edit metadata' })
    .getByLabel('Company name')
    .fill('Phase4E Manual Company Stale');
  await conflictPage.getByRole('button', { name: 'Edit metadata' }).last().click();
  await expect(conflictPage.getByRole('alert')).toContainText('changed elsewhere');
  await expect(
    conflictPage.getByRole('form', { name: 'Edit metadata' }).getByLabel('Company name'),
  ).toHaveValue('Phase4E Manual Company Stale');
  await conflictPage.getByRole('button', { name: 'Reload latest job' }).click();
  await expect(
    conflictPage.getByRole('heading', { name: 'Phase4E Manual Company Saved' }),
  ).toBeVisible();

  await assertNoBrowserPersistence(memberPage, [
    'Phase4E URL Company',
    'Synthetic updated private note.',
    'Synthetic second interview stage.',
    urlJob!.id,
    memberApplication!.id,
  ]);
  await assertNoBrowserPersistence(otherPage, ['Phase4E Other Company', otherApplication.id]);
  await assertNoBrowserPersistence(adminPage, ['Phase4E URL Company', memberApplication!.id]);
  await assertNoBrowserPersistence(conflictPage, ['Phase4E Manual Company Stale']);

  await logout(adminPage);
  await memberContext.close();
  await otherContext.close();
  await adminPage.context().close();
  await conflictContext.close();
});
