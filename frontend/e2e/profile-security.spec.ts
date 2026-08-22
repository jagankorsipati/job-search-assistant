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

async function login(page: Page, loginName: string, password: string) {
  await page.goto('/login');
  await expect(
    page.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  await page.getByLabel('Login name').fill(loginName);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Your job-search workspace.' })).toBeVisible();
}

async function openProfile(page: Page) {
  await page.goto('/profile');
  await expect(page.getByRole('heading', { name: 'Candidate profile' })).toBeVisible();
}

async function createMember(browser: Browser, baseURL: string | undefined) {
  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await login(adminPage, adminLogin, adminPassword);
  await adminPage.getByRole('button', { name: 'Create member invitation' }).click();
  await adminPage.getByRole('button', { name: 'Create MEMBER invitation' }).click();
  const invitationLink = await adminPage.getByLabel('One-time invitation link').inputValue();
  expect(invitationLink.startsWith(`${baseURL}/#invite=`)).toBeTruthy();

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
  await adminContext.close();
  await memberContext.close();
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

async function createProfile(page: Page, displayName: string) {
  await openProfile(page);
  await expect(page.getByText('Create your profile once')).toBeVisible();
  await page.getByLabel(/professional display name/i).fill(displayName);
  await page.getByLabel(/professional headline/i).fill('Synthetic profile specialist');
  await page.getByLabel(/career summary/i).fill('Synthetic summary aligned to confirmed facts.');
  await page.getByLabel(/^location preference/i).fill('Remote');
  await page.getByLabel(/target roles/i).fill('Verification engineer');
  await page.getByLabel(/work authorization statement/i).fill('Synthetic authorization statement.');
  await page.getByLabel(/work-location preferences/i).fill('Remote or hybrid');
  await page.getByRole('button', { name: 'Save profile' }).click();
  await expect(page.getByText(displayName)).toBeVisible();
  await expect(page.getByText('Synthetic profile specialist')).toBeVisible();
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
  await expect(page.getByText(content)).toBeVisible();
  await expect(
    page.getByRole('listitem').filter({ hasText: content }).getByText('Draft'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save career fact' })).toHaveCount(0);
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
  test.setTimeout(120_000);
  await createMember(browser, baseURL);

  const memberContext = await browser.newContext();
  const memberPage = await memberContext.newPage();
  await memberPage.goto('/profile');
  await expect(
    memberPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect((await apiStatusAndCode(memberPage, '/api/profile')).status).toBe(401);

  await login(memberPage, memberLogin, memberPassword);
  await createProfile(memberPage, 'Synthetic Member Profile');
  await memberPage.reload();
  await expect(memberPage.getByRole('heading', { name: 'Candidate profile' })).toBeVisible();
  await expect(memberPage.getByText('Synthetic Member Profile')).toBeVisible();

  await memberPage.getByRole('button', { name: 'Edit profile' }).click();
  await memberPage.getByLabel(/professional display name/i).fill('Unsaved Profile Name');
  await memberPage.getByRole('button', { name: 'Cancel' }).click();
  await expect(memberPage.getByText('Synthetic Member Profile')).toBeVisible();
  await memberPage.getByRole('button', { name: 'Edit profile' }).click();
  await memberPage.getByLabel(/professional display name/i).fill('');
  await memberPage.getByRole('button', { name: 'Save profile' }).click();
  await expect(memberPage.getByText('Professional display name is required.')).toBeVisible();
  await memberPage.getByLabel(/professional display name/i).fill('Synthetic Member Updated');
  await memberPage.getByRole('button', { name: 'Save profile' }).click();
  await expect(memberPage.getByText('Synthetic Member Updated')).toBeVisible();

  await addFact(memberPage, 'Synthetic employment fact for browser verification.', 'EMPLOYMENT');
  await addFact(memberPage, 'Synthetic skill fact for browser verification.', 'SKILL');
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

  await employmentFactCard.getByRole('button', { name: 'Edit' }).click();
  await expect(memberPage.getByText(/return it to draft/i)).toBeVisible();
  await memberPage
    .getByLabel(/factual content/i)
    .fill('Synthetic employment fact edited after confirmation.');
  await memberPage.getByRole('button', { name: 'Save career fact' }).click();
  await expect(
    memberPage.getByText('Synthetic employment fact edited after confirmation.'),
  ).toBeVisible();
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

  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await login(adminPage, adminLogin, adminPassword);
  await createProfile(adminPage, 'Synthetic Admin Profile');
  await addFact(adminPage, 'Synthetic admin-only fact.', 'SKILL');
  await expect(adminPage.getByText('Synthetic Member Updated')).toHaveCount(0);
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
    'Synthetic Member Updated',
    'Synthetic committed concurrent fact.',
    memberLogin,
    memberFact!.id,
  ]);
  await assertNoBrowserPersistence(adminPage, [
    'Synthetic Admin Profile',
    'Synthetic admin-only fact',
  ]);
  await assertNoBrowserPersistence(conflictPage, ['Synthetic stale browser edit.', memberFact!.id]);

  await memberContext.close();
  await adminContext.close();
  await conflictContext.close();
});
