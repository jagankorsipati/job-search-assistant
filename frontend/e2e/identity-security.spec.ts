import { expect, test, type Page } from '@playwright/test';

const adminLogin = process.env.E2E_ADMIN_LOGIN ?? 'e2e.admin';
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
const memberPassword = process.env.E2E_MEMBER_PASSWORD;

if (!adminPassword || !memberPassword) {
  throw new Error('The E2E runner must provide process-scoped test credentials.');
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

async function assertNoIdentityStorage(page: Page) {
  const storage = await page.evaluate(() => ({
    local: Object.keys(localStorage).length,
    session: Object.keys(sessionStorage).length,
  }));
  expect(storage).toEqual({ local: 0, session: 0 });
}

async function meStatus(page: Page) {
  return page.evaluate(async () => (await fetch('/api/auth/me')).status);
}

test('real browser identity lifecycle preserves security boundaries', async ({
  browser,
  baseURL,
}) => {
  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();

  await adminPage.goto('/admin/accounts');
  await expect(
    adminPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect(await meStatus(adminPage)).toBe(401);

  await adminPage.evaluate(async () => fetch('/api/auth/csrf'));
  const anonymousCookie = (await adminContext.cookies()).find(
    (cookie) => cookie.name === 'JSA_SESSION',
  );
  expect(Boolean(anonymousCookie)).toBeTruthy();
  await login(adminPage, adminLogin, adminPassword);

  const authenticatedCookie = (await adminContext.cookies()).find(
    (cookie) => cookie.name === 'JSA_SESSION',
  );
  expect(Boolean(authenticatedCookie)).toBeTruthy();
  expect(anonymousCookie?.value !== authenticatedCookie?.value).toBeTruthy();
  expect(authenticatedCookie).toMatchObject({
    name: 'JSA_SESSION',
    httpOnly: true,
    secure: false,
    sameSite: 'Strict',
    path: '/',
    expires: -1,
  });
  await adminPage.reload();
  await expect(adminPage.getByText('Signed in as admin')).toBeVisible();
  expect(await meStatus(adminPage)).toBe(200);
  await assertNoIdentityStorage(adminPage);

  await adminPage.getByRole('button', { name: 'Create member invitation' }).click();
  await adminPage.getByRole('button', { name: 'Create MEMBER invitation' }).click();
  const invitationLink = await adminPage.getByLabel('One-time invitation link').inputValue();
  expect(invitationLink.startsWith(`${baseURL}/#invite=`)).toBeTruthy();
  const invitationToken = decodeURIComponent(new URL(invitationLink).hash.slice(8));

  await adminPage.getByRole('button', { name: 'Back to workspace' }).click();
  await adminPage.getByRole('button', { name: 'Manage household members' }).click();
  const adminCard = adminPage.getByRole('listitem').filter({ hasText: 'e2e.admin' });
  await expect(adminCard).toBeVisible();
  await expect(adminCard.getByRole('button', { name: /member/i })).toHaveCount(0);

  const acceptanceContext = await browser.newContext();
  const acceptancePage = await acceptanceContext.newPage();
  await acceptancePage.goto(invitationLink);
  await expect(acceptancePage).toHaveURL(`${baseURL}/invite`);
  await expect(acceptancePage.getByLabel('Invitation token')).toHaveCount(0);
  await assertNoIdentityStorage(acceptancePage);
  expect(
    (await acceptanceContext.cookies()).some((cookie) => cookie.value === invitationToken),
  ).toBeFalsy();

  await acceptancePage.getByLabel('Display name').fill('E2E Household Member');
  await acceptancePage.getByLabel('Login name').fill('e2e.member');
  await acceptancePage.getByLabel('Password', { exact: true }).fill(memberPassword);
  await acceptancePage.getByLabel('Confirm password').fill(memberPassword);
  await acceptancePage.getByRole('button', { name: 'Create account' }).click();
  await expect(
    acceptancePage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect(await meStatus(acceptancePage)).toBe(401);
  const acceptedPageText = await acceptancePage.locator('body').innerText();
  expect(acceptedPageText.includes('#invite=')).toBeFalsy();
  expect(acceptedPageText.includes(invitationToken)).toBeFalsy();
  await assertNoIdentityStorage(acceptancePage);

  const invalidContext = await browser.newContext();
  const invalidPage = await invalidContext.newPage();
  await invalidPage.goto(invitationLink);
  await invalidPage.getByLabel('Display name').fill('Another Member');
  await invalidPage.getByLabel('Login name').fill('another.member');
  await invalidPage.getByLabel('Password', { exact: true }).fill(memberPassword);
  await invalidPage.getByLabel('Confirm password').fill(memberPassword);
  await invalidPage.getByRole('button', { name: 'Create account' }).click();
  const reuseMessage = await invalidPage.getByRole('alert').innerText();
  expect(reuseMessage).toBe('This invitation is invalid or no longer available.');
  expect((await invalidPage.locator('body').innerText()).includes(memberPassword)).toBeFalsy();

  const malformedPage = await (await browser.newContext()).newPage();
  await malformedPage.goto('/invite');
  await malformedPage.getByLabel('Invitation token').fill('invalid-token');
  await malformedPage.getByLabel('Display name').fill('Invalid Member');
  await malformedPage.getByLabel('Login name').fill('invalid.member');
  await malformedPage.getByLabel('Password', { exact: true }).fill(memberPassword);
  await malformedPage.getByLabel('Confirm password').fill(memberPassword);
  await malformedPage.getByRole('button', { name: 'Create account' }).click();
  expect(await malformedPage.getByRole('alert').innerText()).toBe(reuseMessage);

  const memberContextOne = await browser.newContext();
  const memberPageOne = await memberContextOne.newPage();
  await login(memberPageOne, 'e2e.member', memberPassword);
  await memberPageOne.reload();
  await expect(memberPageOne.getByText('Signed in as member')).toBeVisible();
  await assertNoIdentityStorage(memberPageOne);
  await memberPageOne.goto('/admin/accounts');
  await expect(
    memberPageOne.getByRole('heading', { name: 'Your job-search workspace.' }),
  ).toBeVisible();
  const adminEndpointStatus = await memberPageOne.evaluate(
    async () => (await fetch('/api/admin/accounts')).status,
  );
  expect(adminEndpointStatus).toBe(403);
  const csrfRejected = await memberPageOne.evaluate(
    async () => (await fetch('/api/auth/logout', { method: 'POST' })).status,
  );
  expect(csrfRejected).toBe(403);

  const memberContextTwo = await browser.newContext();
  const memberPageTwo = await memberContextTwo.newPage();
  await login(memberPageTwo, 'e2e.member', memberPassword);

  await adminPage.goto('/admin/accounts');
  const memberCard = adminPage.getByRole('listitem').filter({ hasText: 'e2e.member' });
  await expect(memberCard).toBeVisible();
  adminPage.once('dialog', (dialog) => dialog.accept());
  await memberCard.getByRole('button', { name: 'Disable member' }).click();
  await expect(memberCard.getByText('DISABLED')).toBeVisible();
  await expect.poll(() => meStatus(memberPageOne)).toBe(401);
  await expect.poll(() => meStatus(memberPageTwo)).toBe(401);

  const disabledContext = await browser.newContext();
  const disabledPage = await disabledContext.newPage();
  await disabledPage.goto('/login');
  await disabledPage.getByLabel('Login name').fill('e2e.member');
  await disabledPage.getByLabel('Password').fill(memberPassword);
  await disabledPage.getByRole('button', { name: 'Sign in' }).click();
  await expect(disabledPage.getByRole('alert')).toHaveText('Login name or password is incorrect.');

  adminPage.once('dialog', (dialog) => dialog.accept());
  await memberCard.getByRole('button', { name: 'Reactivate member' }).click();
  await expect(memberCard.getByText('ACTIVE')).toBeVisible();
  expect(await meStatus(memberPageOne)).toBe(401);
  expect(await meStatus(memberPageTwo)).toBe(401);
  await login(disabledPage, 'e2e.member', memberPassword);

  const oldCookie = (await disabledContext.cookies()).find(
    (cookie) => cookie.name === 'JSA_SESSION',
  );
  expect(Boolean(oldCookie)).toBeTruthy();
  await disabledPage.getByRole('button', { name: 'Sign out' }).click();
  await expect(
    disabledPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect(
    (await disabledContext.cookies()).some((cookie) => cookie.name === 'JSA_SESSION'),
  ).toBeFalsy();
  if (oldCookie) await disabledContext.addCookies([oldCookie]);
  expect(await meStatus(disabledPage)).toBe(401);

  await adminContext.close();
  await acceptanceContext.close();
  await invalidContext.close();
  await memberContextOne.close();
  await memberContextTwo.close();
  await disabledContext.close();
});
