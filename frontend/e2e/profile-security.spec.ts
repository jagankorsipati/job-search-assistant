import { expect, test, type Browser, type Page } from '@playwright/test';

const adminLogin = process.env.E2E_ADMIN_LOGIN ?? 'e2e.admin';
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
const memberPassword = process.env.E2E_MEMBER_PASSWORD;

if (!adminPassword || !memberPassword) {
  throw new Error('The E2E runner must provide process-scoped test credentials.');
}

const runSuffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
const memberLogin = `profile.member.${runSuffix}`;

type FactStatus = 'DRAFT' | 'CONFIRMED' | 'ARCHIVED';
interface FactResponse {
  id: string;
  status: FactStatus;
  version: number;
  factualContent: string;
}
interface BaseResumeResponse {
  id: string;
  originalFilename: string;
  mediaType: string;
  byteSize: number;
  version: number;
}

const syntheticPdfBytes = Buffer.from(
  '%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n',
  'latin1',
);

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
}

async function openProfile(page: Page) {
  await page.goto('/profile');
  await expect(page.getByRole('heading', { name: 'Candidate profile' })).toBeVisible();
}

/**
 * Logs the shared administrator in once and invites a fresh, uniquely named
 * member. The administrator session is intentionally kept open and returned
 * so the caller can reuse it later (for example, to exercise cross-account
 * isolation) instead of logging the fixed administrator account in again,
 * which would needlessly consume the production login-rate-limit budget.
 */
async function loginAdminAndInviteMember(
  browser: Browser,
  baseURL: string | undefined,
): Promise<Page> {
  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await login(adminPage, adminLogin, adminPassword);
  await adminPage.getByRole('button', { name: 'Create member invitation' }).click();
  await adminPage.getByRole('button', { name: 'Create MEMBER invitation' }).click();
  const invitationLink = await adminPage.getByLabel('One-time invitation link').inputValue();
  expect(invitationLink.startsWith(`${baseURL}/#invite=`)).toBeTruthy();
  await adminPage.getByRole('button', { name: 'Back to workspace' }).click();

  const memberContext = await browser.newContext();
  const memberPage = await memberContext.newPage();
  await memberPage.goto(invitationLink);
  await expect(memberPage).toHaveURL(`${baseURL}/invite`);
  await memberPage.getByLabel('Display name').fill('Profile Security Member');
  await memberPage.getByLabel('Login name').fill(memberLogin);
  await memberPage.getByLabel('Password', { exact: true }).fill(memberPassword);
  await memberPage.getByLabel('Confirm password').fill(memberPassword);
  await memberPage.getByRole('button', { name: 'Create account' }).click();
  await expect(
    memberPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  await memberContext.close();
  return adminPage;
}

async function csrf(page: Page) {
  return page.evaluate(async () => {
    const response = await fetch('/api/auth/csrf', { headers: { Accept: 'application/json' } });
    const token = (await response.json()) as { token: string; headerName: string };
    return { token: token.token, headerName: token.headerName };
  });
}

async function apiStatusAndCode(page: Page, path: string, init?: RequestInit) {
  return page.evaluate(
    async ({ path, init }) => {
      const response = await fetch(path, init);
      let code: unknown = undefined;
      try {
        code = ((await response.json()) as { code?: unknown }).code;
      } catch {
        // Some successful or rejected responses have no body useful to this assertion.
      }
      return { status: response.status, code: typeof code === 'string' ? code : undefined };
    },
    { path, init },
  );
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

/**
 * Creates the profile for a brand-new account, or updates it in place if one
 * already exists. The fixed, shared administrator account can already carry
 * a profile left over from an earlier verification pass against the same
 * running backend and database, so this helper detects that state instead of
 * assuming first-time creation and hitting a 409 the assertions can't explain.
 */
async function createProfile(page: Page, displayName: string) {
  await openProfile(page);
  console.info('Profile screen ready for edit.');
  console.info('Submitting profile form.');
  const save = await page.evaluate(async (displayName) => {
    const csrf = await fetch('/api/auth/csrf', { headers: { Accept: 'application/json' } }).then(
      (response) => response.json(),
    );
    const current = await fetch('/api/profile', { headers: { Accept: 'application/json' } });
    const creating = current.status === 404;
    let expectedVersion: unknown;
    if (!creating && current.ok) {
      const profile = (await current.json()) as { version?: unknown };
      expectedVersion = profile.version;
    }
    const response = await fetch('/api/profile', {
      method: creating ? 'POST' : 'PUT',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify({
        professionalDisplayName: displayName,
        professionalHeadline: 'Synthetic profile specialist',
        careerSummary: 'Synthetic summary aligned to confirmed facts.',
        locationPreference: 'Remote',
        targetRoles: 'Verification engineer',
        workAuthorization: 'Synthetic authorization statement.',
        workLocationPreferences: 'Remote or hybrid',
        expectedVersion,
      }),
    });
    let code = 'none';
    try {
      const body = (await response.json()) as { code?: unknown };
      if (typeof body.code === 'string' && /^[a-z0-9_-]{1,64}$/.test(body.code)) code = body.code;
    } catch {
      // Success bodies carry profile content and are intentionally not read here.
    }
    return { status: response.status, code, creating };
  }, displayName);
  // 401 session expired, 403 CSRF rejection, 409 unexpected conflict, 500 server
  // error. Only the safe status and generic code are logged, never the body.
  const safeResponseSummary = `Profile save response: HTTP ${save.status}, generic code ${save.code}`;
  console.info(safeResponseSummary);
  expect(save.status, safeResponseSummary).toBe(save.creating ? 201 : 200);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Candidate profile' })).toBeVisible();
  await expect(page.getByText(displayName)).toBeVisible();
  await expect(page.getByText('Synthetic profile specialist')).toBeVisible();
  console.info('Profile detail visible after save.');
}

async function addFact(page: Page, content: string, category: 'EMPLOYMENT' | 'SKILL') {
  await page.getByRole('button', { name: 'Add career fact' }).click();
  await page
    .locator('form')
    .filter({ hasText: 'Add career fact' })
    .getByLabel('Category')
    .selectOption(category);
  await page.getByLabel(/factual content/i).fill(content);
  await page.getByLabel(/organization/i).fill('Synthetic Organization');
  await page.getByLabel(/title/i).fill('Synthetic Title');
  await page.getByLabel(/location/i).fill('Remote');
  await page.getByLabel(/start date/i).fill('2025-01-01');
  await page.getByLabel(/ongoing/i).check();
  await page.getByRole('button', { name: 'Save career fact' }).click();
  const factItem = page.getByRole('listitem').filter({ hasText: content }).last();
  await expect(factItem).toBeVisible();
  await expect(factItem.getByText('Draft')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save career fact' })).toHaveCount(0);
}

async function uploadBaseResume(page: Page, filename: string, bytes: Buffer) {
  await page.getByLabel(/resume file/i).setInputFiles({
    name: filename,
    mimeType: 'application/pdf',
    buffer: bytes,
  });
  await expect(
    page.getByText(new RegExp(`Selected: ${filename.replace('.', '\\.')}`)),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Upload base resume' }).click();
  await expect(page.getByText('Base resume uploaded.')).toBeVisible();
  await expect(page.getByText(filename)).toBeVisible();
}

async function replaceBaseResume(page: Page, filename: string, bytes: Buffer) {
  await page.getByRole('button', { name: 'Replace' }).click();
  await page.getByLabel(/resume file/i).setInputFiles({
    name: filename,
    mimeType: 'application/pdf',
    buffer: bytes,
  });
  await expect(
    page.getByText(new RegExp(`Selected: ${filename.replace('.', '\\.')}`)),
  ).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Replace base resume' }).click();
}

async function downloadBaseResume(page: Page) {
  return page.evaluate(async () => {
    const response = await fetch('/api/documents/base-resume/download');
    const bytes = Array.from(new Uint8Array(await response.arrayBuffer()));
    return {
      status: response.status,
      contentType: response.headers.get('content-type') ?? '',
      disposition: response.headers.get('content-disposition') ?? '',
      nosniff: response.headers.get('x-content-type-options') ?? '',
      cacheControl: response.headers.get('cache-control') ?? '',
      bytes,
    };
  });
}

async function assertNoBrowserPersistence(page: Page, forbidden: string[]) {
  const result = await page.evaluate(async (forbiddenValues) => {
    const storage = {
      local: Object.keys(localStorage).length,
      session: Object.keys(sessionStorage).length,
    };
    const databases =
      'databases' in indexedDB
        ? await indexedDB.databases().then((items) => items.map((item) => item.name ?? ''))
        : [];
    const url = window.location.href;
    const cookie = document.cookie;
    const source = document.documentElement.innerHTML;
    return {
      storage,
      databases,
      urlHasForbidden: forbiddenValues.some((value) => value !== '' && url.includes(value)),
      cookieHasForbidden: forbiddenValues.some((value) => value !== '' && cookie.includes(value)),
      sourceHasUnsafe:
        /owner_account_id|stack trace|SQLException|JSA_SESSION|XSRF|csrf/i.test(source) ||
        forbiddenValues.some((value) => value !== '' && cookie.includes(value)),
    };
  }, forbidden);
  expect(result.storage).toEqual({ local: 0, session: 0 });
  expect(result.databases.filter((name) => /profile|career|job-search/i.test(name))).toEqual([]);
  expect(result.urlHasForbidden).toBeFalsy();
  expect(result.cookieHasForbidden).toBeFalsy();
  expect(result.sourceHasUnsafe).toBeFalsy();
}

test('real browser profile lifecycle, isolation, conflicts, csrf, and privacy', async ({
  browser,
  baseURL,
}) => {
  test.setTimeout(600_000);
  const adminPage = await loginAdminAndInviteMember(browser, baseURL);
  const adminContext = adminPage.context();

  const memberContext = await browser.newContext();
  const memberPage = await memberContext.newPage();
  await memberPage.goto('/profile');
  await expect(
    memberPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect((await apiStatusAndCode(memberPage, '/api/profile')).status).toBe(401);

  await login(memberPage, memberLogin, memberPassword);
  await createProfile(memberPage, 'Synthetic Member Profile');
  await expect(memberPage.getByText('No base resume is stored for your account.')).toBeVisible();
  await expect(
    memberPage.getByText(/does not confirm, import, or change your career facts/i),
  ).toBeVisible();
  await memberPage.getByLabel(/resume file/i).setInputFiles({
    name: 'not-a-resume.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('plain text'),
  });
  await expect(memberPage.getByRole('alert')).toContainText('PDF or DOCX');
  await uploadBaseResume(memberPage, 'synthetic-base-resume.pdf', syntheticPdfBytes);
  console.info('Member base resume uploaded.');
  await memberPage.reload();
  await expect(memberPage.getByText('synthetic-base-resume.pdf')).toBeVisible();
  const downloadedResume = await downloadBaseResume(memberPage);
  expect(downloadedResume.status).toBe(200);
  expect(downloadedResume.contentType).toContain('application/pdf');
  expect(downloadedResume.disposition).toContain('attachment');
  expect(downloadedResume.nosniff).toBe('nosniff');
  expect(downloadedResume.cacheControl).toContain('no-store');
  expect(downloadedResume.bytes).toEqual(Array.from(syntheticPdfBytes));
  console.info('Member base resume download verified.');
  const memberResume = await apiJson<BaseResumeResponse>(memberPage, '/api/documents/base-resume');
  expect(memberResume.originalFilename).toBe('synthetic-base-resume.pdf');
  expect(JSON.stringify(memberResume)).not.toContain('storageKey');
  expect(memberResume.sha256Checksum).toMatch(/^[0-9a-f]{64}$/);
  await memberPage.reload();
  await expect(memberPage.getByRole('heading', { name: 'Candidate profile' })).toBeVisible();
  await expect(memberPage.getByText('Synthetic Member Profile')).toBeVisible();
  console.info('Member profile reload verified.');

  await addFact(memberPage, 'Synthetic employment fact for browser verification.', 'EMPLOYMENT');
  await addFact(memberPage, 'Synthetic skill fact for browser verification.', 'SKILL');
  console.info('Member career facts added.');
  const memberCategoryFilter = memberPage.locator('.filter-bar select').first();
  const memberStatusFilter = memberPage.locator('.filter-bar select').nth(1);
  await memberCategoryFilter.selectOption('SKILL');
  await expect(
    memberPage.getByText('Synthetic skill fact for browser verification.'),
  ).toBeVisible();
  await expect(
    memberPage.getByText('Synthetic employment fact for browser verification.'),
  ).toHaveCount(0);
  await memberStatusFilter.selectOption('CONFIRMED');
  await expect(memberPage.getByText('No career facts match the selected filters.')).toBeVisible();
  await memberCategoryFilter.selectOption('');
  await memberStatusFilter.selectOption('');
  console.info('Member career fact filters verified.');

  const employmentFactCard = memberPage
    .getByRole('listitem')
    .filter({ hasText: 'Synthetic employment fact for browser verification.' });
  await employmentFactCard.getByRole('button', { name: 'Confirm as accurate' }).click();
  const disabledConfirm = employmentFactCard.getByRole('button', { name: 'Confirm as accurate' });
  await expect(disabledConfirm).toBeDisabled();
  await employmentFactCard
    .getByRole('checkbox', { name: /I confirm that this career fact is accurate/i })
    .check();
  await disabledConfirm.click();
  await expect(memberPage.getByText('Career fact confirmed.')).toBeVisible();
  await expect(memberPage.getByText('Confirmed means owner-attested')).toBeVisible();
  console.info('Member career fact confirmation verified.');

  await employmentFactCard.getByRole('button', { name: 'Edit' }).click();
  await expect(memberPage.getByText(/return it to draft/i)).toBeVisible();
  await memberPage
    .getByLabel(/factual content/i)
    .fill('Synthetic employment fact edited after confirmation.');
  await memberPage.getByRole('button', { name: 'Save career fact' }).click();
  await expect(
    memberPage.getByText('Synthetic employment fact edited after confirmation.'),
  ).toBeVisible();
  console.info('Member career fact edit after confirmation verified.');
  const editedEmploymentFactCard = memberPage
    .getByRole('listitem')
    .filter({ hasText: 'Synthetic employment fact edited after confirmation.' });
  await expect(editedEmploymentFactCard.getByText('Draft')).toBeVisible();

  memberPage.once('dialog', (dialog) => dialog.dismiss());
  await editedEmploymentFactCard.getByRole('button', { name: 'Archive' }).click();
  await expect(
    memberPage.getByText('Synthetic employment fact edited after confirmation.'),
  ).toBeVisible();
  memberPage.once('dialog', (dialog) => dialog.accept());
  await editedEmploymentFactCard.getByRole('button', { name: 'Archive' }).click();
  await expect(memberPage.getByText('Career fact archived.')).toBeVisible();
  console.info('Member career fact archive verified.');
  await expect(
    memberPage
      .getByRole('listitem')
      .filter({ hasText: 'Synthetic employment fact edited after confirmation.' })
      .getByRole('button', { name: 'Edit', exact: true }),
  ).toHaveCount(0);
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage.getByRole('button', { name: 'Restore to draft' }).first().click();
  await expect(memberPage.getByText('Career fact restored to draft.')).toBeVisible();
  await memberStatusFilter.selectOption('ARCHIVED');
  await expect(memberPage.getByText('No career facts match the selected filters.')).toBeVisible();
  await memberStatusFilter.selectOption('');
  await memberPage.reload();
  await expect(
    memberPage.getByText('Synthetic employment fact edited after confirmation.'),
  ).toBeVisible();

  const memberFacts = await apiJson<FactResponse[]>(
    memberPage,
    '/api/profile/career-facts?limit=100',
  );
  const memberFact = memberFacts.find((fact) =>
    fact.factualContent.includes('employment fact edited'),
  );
  expect(Boolean(memberFact)).toBeTruthy();
  const randomUuid = '11111111-2222-4333-8444-555555555555';
  const directMissing = await apiStatusAndCode(
    memberPage,
    `/api/profile/career-facts/${randomUuid}`,
  );

  // The administrator session opened in loginAdminAndInviteMember is reused
  // here rather than logging the shared administrator account in again.
  await createProfile(adminPage, 'Synthetic Admin Profile');
  await expect(adminPage.getByText('synthetic-base-resume.pdf')).toHaveCount(0);
  if (await adminPage.getByRole('button', { name: 'Replace' }).isVisible()) {
    await replaceBaseResume(adminPage, 'synthetic-admin-resume.pdf', syntheticPdfBytes);
    await expect(adminPage.getByText('Base resume replaced.')).toBeVisible();
    await expect(adminPage.getByText('synthetic-admin-resume.pdf')).toBeVisible();
  } else {
    const adminResumeMissing = await apiStatusAndCode(adminPage, '/api/documents/base-resume');
    expect(adminResumeMissing.status).toBe(404);
    const adminResumeDownloadMissing = await apiStatusAndCode(
      adminPage,
      '/api/documents/base-resume/download',
    );
    expect(adminResumeDownloadMissing.status).toBe(404);
    await uploadBaseResume(adminPage, 'synthetic-admin-resume.pdf', syntheticPdfBytes);
  }
  console.info('Admin base resume uploaded.');
  await expect(memberPage.getByText('synthetic-admin-resume.pdf')).toHaveCount(0);
  await addFact(adminPage, 'Synthetic admin-only fact.', 'SKILL');
  await expect(adminPage.getByText('Synthetic Member Profile')).toHaveCount(0);
  await expect(
    adminPage.getByText('Synthetic employment fact edited after confirmation.'),
  ).toHaveCount(0);

  const adminFacts = await apiJson<FactResponse[]>(
    adminPage,
    '/api/profile/career-facts?limit=100',
  );
  expect(adminFacts.some((fact) => fact.factualContent.includes('admin-only'))).toBeTruthy();
  expect(
    adminFacts.some((fact) => fact.factualContent.includes('employment fact edited')),
  ).toBeFalsy();

  const adminMissing = await apiStatusAndCode(
    adminPage,
    `/api/profile/career-facts/${memberFact!.id}`,
  );
  expect(adminMissing).toEqual(directMissing);
  const adminCsrf = await csrf(adminPage);
  const crossUserInit = {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [adminCsrf.headerName]: adminCsrf.token,
    },
    body: JSON.stringify({
      ownerAccountId: '00000000-0000-4000-8000-000000000000',
      accountId: '00000000-0000-4000-8000-000000000000',
      category: 'EMPLOYMENT',
      factualContent: 'Synthetic cross-user mutation attempt.',
      organization: 'Synthetic Organization',
      title: 'Synthetic Title',
      location: 'Remote',
      startedOn: '2025-01-01',
      endedOn: null,
      ongoing: true,
      expectedVersion: memberFact!.version,
    }),
  };
  expect(
    (
      await apiStatusAndCode(
        adminPage,
        `/api/profile/career-facts/${memberFact!.id}`,
        crossUserInit,
      )
    ).status,
  ).toBe(404);
  for (const action of ['confirm', 'archive', 'restore']) {
    const status = await apiStatusAndCode(
      adminPage,
      `/api/profile/career-facts/${memberFact!.id}/${action}`,
      {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          [adminCsrf.headerName]: adminCsrf.token,
        },
        body: JSON.stringify({
          expectedVersion: memberFact!.version,
          confirmedAccurate: true,
          ownerAccountId: '00000000-0000-4000-8000-000000000000',
        }),
      },
    );
    expect(status.status).toBe(404);
  }
  expect((await apiStatusAndCode(adminPage, '/api/profile', { method: 'PUT' })).status).toBe(403);

  const refreshedMemberFacts = await apiJson<FactResponse[]>(
    memberPage,
    '/api/profile/career-facts?limit=100',
  );
  expect(
    refreshedMemberFacts.some((fact) =>
      fact.factualContent.includes('Synthetic cross-user mutation attempt.'),
    ),
  ).toBeFalsy();
  await memberPage.reload();
  await expect(memberPage.getByText('Synthetic Admin Profile')).toHaveCount(0);
  await expect(memberPage.getByText('Synthetic admin-only fact.')).toHaveCount(0);

  const conflictContext = await browser.newContext();
  const conflictPage = await conflictContext.newPage();
  await login(conflictPage, memberLogin, memberPassword);
  await openProfile(conflictPage);
  await expect(conflictPage.getByText('synthetic-base-resume.pdf')).toBeVisible();
  const replacementPdfBytes = Buffer.from(
    '%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<<>>\n%%EOF\n',
    'latin1',
  );
  await replaceBaseResume(memberPage, 'synthetic-replacement-resume.pdf', replacementPdfBytes);
  await expect(memberPage.getByText('Base resume replaced.')).toBeVisible();
  await expect(memberPage.getByText('synthetic-replacement-resume.pdf')).toBeVisible();
  console.info('Base resume replacement visible.');
  await replaceBaseResume(conflictPage, 'synthetic-stale-resume.pdf', replacementPdfBytes);
  await expect(conflictPage.getByRole('alert')).toContainText('base resume changed elsewhere');
  await conflictPage.getByRole('button', { name: 'Reload latest resume' }).click();
  await expect(conflictPage.getByText('synthetic-replacement-resume.pdf')).toBeVisible();
  await expect(
    conflictPage.getByText('Synthetic employment fact edited after confirmation.'),
  ).toBeVisible();
  await memberPage
    .getByRole('listitem')
    .filter({ hasText: 'Synthetic employment fact edited after confirmation.' })
    .getByRole('button', { name: 'Edit', exact: true })
    .click();
  await conflictPage
    .getByRole('listitem')
    .filter({ hasText: 'Synthetic employment fact edited after confirmation.' })
    .getByRole('button', { name: 'Edit', exact: true })
    .click();
  await memberPage.getByLabel(/factual content/i).fill('Synthetic committed concurrent fact.');
  await memberPage.getByRole('button', { name: 'Save career fact' }).click();
  await expect(memberPage.getByText('Synthetic committed concurrent fact.')).toBeVisible();
  await conflictPage.getByLabel(/factual content/i).fill('Synthetic stale browser edit.');
  await conflictPage.getByRole('button', { name: 'Save career fact' }).click();
  await expect(conflictPage.getByRole('alert')).toContainText('changed elsewhere');
  await expect(conflictPage.getByLabel(/factual content/i)).toHaveValue(
    'Synthetic stale browser edit.',
  );
  await expect(memberPage.getByText('Synthetic stale browser edit.')).toHaveCount(0);
  await conflictPage.getByRole('button', { name: 'Reload latest version' }).click();
  await expect(conflictPage.getByText('Synthetic committed concurrent fact.')).toBeVisible();

  const memberCsrflessStatus = await apiStatusAndCode(memberPage, '/api/profile', {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ professionalDisplayName: 'No CSRF', expectedVersion: 0 }),
  });
  expect(memberCsrflessStatus.status).toBe(403);

  await assertNoBrowserPersistence(memberPage, [
    'Synthetic Member Profile',
    'Synthetic committed concurrent fact.',
    'synthetic-replacement-resume.pdf',
    memberLogin,
    memberFact!.id,
    memberResume.id,
  ]);
  await assertNoBrowserPersistence(adminPage, [
    'Synthetic Admin Profile',
    'Synthetic admin-only fact',
    'synthetic-admin-resume.pdf',
  ]);
  await assertNoBrowserPersistence(conflictPage, ['Synthetic stale browser edit.', memberFact!.id]);

  await memberContext.close();
  await adminContext.close();
  await conflictContext.close();
  console.info('Profile browser journey completed.');
});
