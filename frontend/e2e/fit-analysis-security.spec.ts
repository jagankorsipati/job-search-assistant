import { expect, test, type Browser, type Page } from '@playwright/test';

const adminLogin = process.env.E2E_ADMIN_LOGIN ?? 'e2e.admin';
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
const memberPassword = process.env.E2E_MEMBER_PASSWORD;

if (!adminPassword || !memberPassword) {
  throw new Error('The E2E runner must provide process-scoped test credentials.');
}

const runSuffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
const memberLogin = `fit.member.${runSuffix}`;
const otherLogin = `fit.other.${runSuffix}`;

type RequirementImportance = 'REQUIRED' | 'PREFERRED' | 'UNSPECIFIED';
type RequirementStatus = 'DRAFT' | 'CONFIRMED' | 'REJECTED';
type EvidenceType = 'CAREER_FACT' | 'PROFILE_FIELD' | 'RESUME_VERSION';
type EvidenceRelationship = 'SUPPORTS' | 'PARTIALLY_SUPPORTS' | 'CONTRADICTS' | 'NOT_DEMONSTRATED';

interface CapturedJob {
  id: string;
  companyName: string;
  jobTitle: string;
  version: number;
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

interface CareerFact {
  id: string;
  status: string;
  version: number;
}

interface Requirement {
  id: string;
  jobId: string;
  jobSnapshotId: string;
  category: string;
  importance: RequirementImportance;
  requirementText: string;
  sourceExcerpt?: string | null;
  status: RequirementStatus;
  version: number;
}

interface EvidenceLink {
  id: string;
  jobRequirementId: string;
  evidenceType: EvidenceType;
  evidenceId: string;
  relationship: EvidenceRelationship;
  userNote?: string | null;
  version: number;
}

interface FitAnalysis {
  policyVersion: string;
  analysisStatus: string;
  evidenceSupportScore?: number | null;
  evidenceCoverageScore?: number | null;
  confirmedRequirementCount: number;
  draftRequirementCount: number;
  rejectedRequirementCount: number;
  totalEligibleWeight: number;
  supportPoints: number;
  assessedWeight: number;
  importanceBreakdowns: Array<{
    importance: RequirementImportance;
    applicable: boolean;
    confirmedRequirementCount: number;
    totalWeight: number;
    assessedCount: number;
    evidenceSupportScore?: number | null;
    evidenceCoverageScore?: number | null;
  }>;
  requirementAssessments: Array<{
    requirementId: string;
    requirementText: string;
    importance: RequirementImportance;
    assessment: string;
    requirementWeight: number;
    evidenceCredit: number;
    weightedContribution: number;
    evidenceLinks: Array<{ relationship: EvidenceRelationship; userNote?: string | null }>;
  }>;
  gaps: Array<{ findingType: string; requirementId: string }>;
  contradictions: Array<{ findingType: string; requirementId: string }>;
}

async function login(page: Page, loginName: string, password: string) {
  await page.goto('/login');
  await expect(
    page.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  await page.getByLabel('Login name').fill(loginName);
  await page.getByLabel('Password').fill(password);
  const responsePromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/auth/login' &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Sign in' }).click();
  const response = await responsePromise;
  let genericCode = 'none';
  try {
    const body = (await response.json()) as { code?: unknown };
    if (typeof body.code === 'string' && /^[a-z0-9_-]{1,64}$/.test(body.code)) {
      genericCode = body.code;
    }
  } catch {
    // Successful login responses do not need a generic code.
  }
  const summary = `Login response: HTTP ${response.status()}, generic code ${genericCode}`;
  console.info(summary);
  expect(response.status(), summary).toBe(200);
  await expect(page.getByRole('heading', { name: 'Your job-search workspace.' })).toBeVisible();
}

async function loginAdmin(browser: Browser): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await login(page, adminLogin, adminPassword);
  return page;
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

  const page = await (await browser.newContext()).newPage();
  await page.goto(invitationLink);
  await expect(page).toHaveURL(`${baseURL}/invite`);
  await page.getByLabel('Display name').fill(displayName);
  await page.getByLabel('Login name').fill(loginName);
  await page.getByLabel('Password', { exact: true }).fill(memberPassword);
  await page.getByLabel('Confirm password').fill(memberPassword);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(
    page.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  await page.context().close();
}

async function csrf(page: Page) {
  return page.evaluate(async () => {
    const response = await fetch('/api/auth/csrf', { headers: { Accept: 'application/json' } });
    const body = (await response.json()) as { token: string; headerName: string };
    return { token: body.token, headerName: body.headerName };
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
        // Some safe responses have no useful body.
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

async function apiStatusWithCsrf(page: Page, path: string, body: unknown, method = 'POST') {
  const token = await csrf(page);
  return apiStatusAndShape(page, path, {
    method,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [token.headerName]: token.token,
    },
    body: JSON.stringify(body),
  });
}

async function createProfileAndFacts(page: Page) {
  await apiWrite(page, '/api/profile', {
    professionalDisplayName: 'Synthetic Fit Member',
    professionalHeadline: 'Synthetic fit verification specialist',
    careerSummary: 'Synthetic summary for browser fit verification.',
    locationPreference: 'Remote',
    targetRoles: 'Synthetic platform role',
    workAuthorizationStatement: 'Synthetic work authorization.',
    workLocationPreferences: 'Remote synthetic preference.',
  });
  const draftFact = await apiWrite<CareerFact>(page, '/api/profile/career-facts', {
    category: 'SKILL',
    factualContent: 'Synthetic draft fact that must not be selectable.',
    organization: 'Synthetic Org',
    title: 'Synthetic Role',
    location: 'Remote',
    startedOn: '2025-01-01',
    endedOn: null,
    ongoing: true,
  });
  const confirmedFact = await apiWrite<CareerFact>(page, '/api/profile/career-facts', {
    category: 'SKILL',
    factualContent: 'Synthetic confirmed evidence for deterministic fit.',
    organization: 'Synthetic Org',
    title: 'Synthetic Role',
    location: 'Remote',
    startedOn: '2025-01-01',
    endedOn: null,
    ongoing: true,
  });
  const confirmed = await apiWrite<CareerFact>(
    page,
    `/api/profile/career-facts/${confirmedFact.id}/confirm`,
    { expectedVersion: confirmedFact.version, confirmedAccurate: true },
  );
  return { confirmedFact: confirmed, draftFact };
}

async function captureJob(page: Page, companyName: string, descriptionText: string) {
  return apiWrite<CaptureJobResponse>(page, '/api/jobs', {
    companyName,
    jobTitle: 'Synthetic Fit Role',
    workLocation: 'Remote',
    postingUrl: 'https://phase5e-posting.example.invalid/jobs/fit',
    sourceType: 'PASTED_DESCRIPTION',
    employmentType: 'FULL_TIME',
    externalPostingId: `P5E-${runSuffix}`,
    descriptionText,
  });
}

async function createRequirement(
  page: Page,
  jobId: string,
  snapshotId: string,
  text: string,
  importance: RequirementImportance,
  status: RequirementStatus,
  category = 'SKILL',
) {
  return apiWrite<Requirement>(page, `/api/jobs/${jobId}/snapshots/${snapshotId}/requirements`, {
    category,
    importance,
    requirementText: text,
    sourceExcerpt: `${text} source excerpt`,
    status,
    ownerAccountId: '00000000-0000-4000-8000-000000000000',
  });
}

async function updateRequirement(
  page: Page,
  requirement: Requirement,
  fields: Partial<Requirement> & { expectedVersion?: number },
) {
  return apiWrite<Requirement>(
    page,
    `/api/job-requirements/${requirement.id}`,
    {
      category: fields.category ?? requirement.category,
      importance: fields.importance ?? requirement.importance,
      requirementText: fields.requirementText ?? requirement.requirementText,
      sourceExcerpt:
        fields.sourceExcerpt === undefined ? requirement.sourceExcerpt : fields.sourceExcerpt,
      status: fields.status ?? requirement.status,
      expectedVersion: fields.expectedVersion ?? requirement.version,
    },
    'PUT',
  );
}

async function createLink(
  page: Page,
  requirement: Requirement,
  evidenceId: string,
  relationship: EvidenceRelationship,
  note?: string,
  evidenceType: EvidenceType = 'CAREER_FACT',
) {
  return apiWrite<EvidenceLink>(page, `/api/job-requirements/${requirement.id}/evidence-links`, {
    evidenceType,
    evidenceId,
    relationship,
    userNote: note ?? null,
    accountId: '00000000-0000-4000-8000-000000000000',
  });
}

async function analysis(page: Page, jobId: string, snapshotId: string) {
  return apiJson<FitAnalysis>(page, `/api/jobs/${jobId}/snapshots/${snapshotId}/fit-analysis`);
}

async function openFit(page: Page, job: CapturedJob, snapshot: Snapshot) {
  await page.goto(`/jobs/${job.id}/snapshots/${snapshot.id}/fit`);
  await expect(page.getByRole('heading', { name: 'Review fit for this snapshot' })).toBeVisible();
}

async function assertNoBrowserPersistence(page: Page, forbidden: string[]) {
  const result = await page.evaluate(async (forbiddenValues) => {
    const databases =
      'databases' in indexedDB
        ? await indexedDB.databases().then((items) => items.map((item) => item.name ?? ''))
        : [];
    const url = window.location.href;
    const cookie = document.cookie;
    return {
      local: Object.keys(localStorage).length,
      session: Object.keys(sessionStorage).length,
      databases,
      urlHasForbidden: forbiddenValues.some((value) => value !== '' && url.includes(value)),
      cookieHasForbidden: forbiddenValues.some((value) => value !== '' && cookie.includes(value)),
      unsafeCookie: /requirement|evidence|score|description|note|csrf|xsrf/i.test(cookie),
    };
  }, forbidden);
  expect(result.local).toBe(0);
  expect(result.session).toBe(0);
  expect(
    result.databases.filter((name) => /fit|job|application|resume|profile/i.test(name)),
  ).toEqual([]);
  expect(result.urlHasForbidden).toBeFalsy();
  expect(result.cookieHasForbidden).toBeFalsy();
  expect(result.unsafeCookie).toBeFalsy();
}

function assessmentFor(body: FitAnalysis, requirement: Requirement) {
  const found = body.requirementAssessments.find((item) => item.requirementId === requirement.id);
  expect(found, 'confirmed requirement appears exactly once in analysis').toBeTruthy();
  expect(
    body.requirementAssessments.filter((item) => item.requirementId === requirement.id),
  ).toHaveLength(1);
  return found!;
}

function requirementCard(page: Page, status: RequirementStatus, text: string) {
  return page.getByLabel(statusLabel(status)).getByRole('listitem').filter({ hasText: text });
}

function statusLabel(status: RequirementStatus) {
  return status[0] + status.slice(1).toLowerCase();
}

test('real browser fit-analysis lifecycle, isolation, conflicts, csrf, privacy, and truthfulness', async ({
  browser,
  baseURL,
}) => {
  test.setTimeout(300_000);
  const adminPage = await loginAdmin(browser);
  await inviteMember(adminPage, browser, baseURL, memberLogin, 'Fit Security Member');
  await inviteMember(adminPage, browser, baseURL, otherLogin, 'Other Fit Member');

  const anonymousPage = await (await browser.newContext()).newPage();
  await anonymousPage.goto(
    '/jobs/11111111-2222-4333-8444-555555555555/snapshots/11111111-2222-4333-8444-555555555555/fit',
  );
  await expect(
    anonymousPage.getByRole('heading', { name: 'Sign in to your private workspace' }),
  ).toBeVisible();
  expect(
    (
      await apiStatusAndShape(
        anonymousPage,
        '/api/jobs/11111111-2222-4333-8444-555555555555/snapshots/11111111-2222-4333-8444-555555555555/fit-analysis',
      )
    ).status,
  ).toBe(401);

  const memberContext = await browser.newContext();
  const memberPage = await memberContext.newPage();
  const contactedHosts = new Set<string>();
  memberPage.on('request', (request) => {
    const url = new URL(request.url());
    contactedHosts.add(url.host);
  });
  await login(memberPage, memberLogin, memberPassword);
  const { confirmedFact, draftFact } = await createProfileAndFacts(memberPage);
  const captured = await captureJob(
    memberPage,
    'Phase5E Synthetic Company',
    'Synthetic immutable description requiring deterministic browser verification.',
  );
  const job = captured.job;
  const snapshot = captured.initialSnapshot!;

  await memberPage.goto('/jobs');
  await expect(memberPage.getByRole('heading', { name: 'Jobs' })).toBeVisible();
  await memberPage.getByRole('button', { name: /Phase5E Synthetic Company/ }).click();
  await memberPage.getByRole('button', { name: 'Review fit' }).click();
  await expect(memberPage).toHaveURL(`/jobs/${job.id}/snapshots/${snapshot.id}/fit`);
  await expect(
    memberPage.getByRole('heading', { name: 'Review fit for this snapshot' }),
  ).toBeVisible();
  await expect(
    memberPage.getByText(/requirements are interpretations of this exact snapshot/i),
  ).toBeVisible();
  await expect(memberPage.getByText(/does not predict hiring decisions/i)).toBeVisible();
  await expect(memberPage.getByText(/evidence relationships you selected/i)).toBeVisible();
  await expect(memberPage.getByText(/verify qualifications independently/i)).toBeVisible();
  await expect(memberPage.getByText(/no draft requirements/i)).toBeVisible();
  expect(memberPage.url()).not.toContain('deterministic');
  expect(memberPage.url()).not.toContain('owner');
  expect(
    [...contactedHosts].some((host) => host.includes('phase5e-posting.example.invalid')),
  ).toBeFalsy();

  await memberPage.reload();
  await expect(
    memberPage.getByRole('heading', { name: 'Review fit for this snapshot' }),
  ).toBeVisible();
  let currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(currentAnalysis.analysisStatus).toBe('NO_CONFIRMED_REQUIREMENTS');
  expect(currentAnalysis.evidenceSupportScore).toBeNull();
  expect(currentAnalysis.evidenceCoverageScore).toBeNull();
  await expect(memberPage.getByText(/confirm at least one reviewed requirement/i)).toBeVisible();
  await expect(memberPage.getByText(/evidence support: 0%/i)).toHaveCount(0);
  await expect(memberPage.getByText(/review coverage: 100%/i)).toHaveCount(0);

  await memberPage.getByRole('button', { name: 'Add requirement' }).click();
  const addForm = memberPage.getByRole('form', { name: 'Add draft requirement' });
  await expect(addForm).toBeVisible();
  await addForm.getByLabel('Requirement text').fill('Synthetic UI draft requirement');
  await expect(addForm.getByLabel('Review status')).toHaveValue('DRAFT');
  await addForm.getByRole('button', { name: 'Cancel' }).click();
  await expect(memberPage.getByText('Synthetic UI draft requirement')).toHaveCount(0);

  const required = await createRequirement(
    memberPage,
    job.id,
    snapshot.id,
    'Synthetic required deterministic skill',
    'REQUIRED',
    'CONFIRMED',
    'SKILL',
  );
  let preferred = await createRequirement(
    memberPage,
    job.id,
    snapshot.id,
    'Synthetic preferred platform practice',
    'PREFERRED',
    'CONFIRMED',
    'RESPONSIBILITY',
  );
  let unspecified = await createRequirement(
    memberPage,
    job.id,
    snapshot.id,
    'Synthetic unspecified documentation habit',
    'UNSPECIFIED',
    'DRAFT',
    'OTHER',
  );
  const draft = await createRequirement(
    memberPage,
    job.id,
    snapshot.id,
    'Synthetic draft excluded requirement',
    'REQUIRED',
    'DRAFT',
    'EXPERIENCE',
  );
  const rejected = await createRequirement(
    memberPage,
    job.id,
    snapshot.id,
    'Synthetic rejected excluded requirement',
    'PREFERRED',
    'REJECTED',
    'CERTIFICATION',
  );
  const deletable = await createRequirement(
    memberPage,
    job.id,
    snapshot.id,
    'Synthetic unlinked requirement to delete',
    'UNSPECIFIED',
    'DRAFT',
    'EDUCATION',
  );

  await memberPage.reload();
  await expect(requirementCard(memberPage, 'CONFIRMED', required.requirementText)).toBeVisible();
  await expect(requirementCard(memberPage, 'DRAFT', draft.requirementText)).toBeVisible();
  await expect(requirementCard(memberPage, 'REJECTED', rejected.requirementText)).toBeVisible();
  currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(currentAnalysis.confirmedRequirementCount).toBe(2);
  expect(currentAnalysis.draftRequirementCount).toBe(3);
  expect(currentAnalysis.rejectedRequirementCount).toBe(1);

  await createLink(memberPage, required, confirmedFact.id, 'SUPPORTS', 'Synthetic supports note.');
  await memberPage.getByRole('button', { name: 'Refresh analysis' }).click();
  await expect(memberPage.getByText(/evidence support: 67%/i)).toBeVisible();
  await expect(memberPage.getByText(/review coverage: 67%/i)).toBeVisible();
  currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(currentAnalysis).toMatchObject({
    policyVersion: 'DETERMINISTIC_FIT_V1',
    evidenceSupportScore: 67,
    evidenceCoverageScore: 67,
    totalEligibleWeight: 3,
    supportPoints: 2,
    assessedWeight: 2,
  });
  expect(assessmentFor(currentAnalysis, required)).toMatchObject({
    assessment: 'DEMONSTRATED',
    requirementWeight: 2,
    weightedContribution: 2,
  });
  expect(assessmentFor(currentAnalysis, preferred)).toMatchObject({ assessment: 'UNASSESSED' });
  await expect(memberPage.getByText(/multiple links do not increase/i)).toBeVisible();
  await expect(memberPage.getByText(/draft and rejected requirements are excluded/i)).toBeVisible();

  const duplicateSupport = await createLink(
    memberPage,
    required,
    '00000000-0000-0000-0000-000000000002',
    'SUPPORTS',
    'Synthetic profile support note.',
    'PROFILE_FIELD',
  );
  currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(currentAnalysis.evidenceSupportScore).toBe(67);
  expect(assessmentFor(currentAnalysis, required).weightedContribution).toBe(2);

  await createLink(
    memberPage,
    preferred,
    confirmedFact.id,
    'PARTIALLY_SUPPORTS',
    'Synthetic partial note.',
  );
  currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(currentAnalysis.evidenceSupportScore).toBe(83);
  expect(currentAnalysis.evidenceCoverageScore).toBe(100);
  expect(assessmentFor(currentAnalysis, preferred)).toMatchObject({
    assessment: 'PARTIALLY_DEMONSTRATED',
    evidenceCredit: 0.5,
    weightedContribution: 0.5,
  });

  unspecified = await updateRequirement(memberPage, unspecified, { status: 'CONFIRMED' });
  await createLink(memberPage, unspecified, confirmedFact.id, 'NOT_DEMONSTRATED');
  currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(currentAnalysis.evidenceSupportScore).toBe(63);
  expect(currentAnalysis.evidenceCoverageScore).toBe(100);
  expect(assessmentFor(currentAnalysis, unspecified)).toMatchObject({
    assessment: 'NOT_DEMONSTRATED',
    weightedContribution: 0,
  });

  await apiWrite(
    memberPage,
    `/api/job-requirement-evidence/${duplicateSupport.id}`,
    {
      evidenceType: duplicateSupport.evidenceType,
      evidenceId: duplicateSupport.evidenceId,
      relationship: 'CONTRADICTS',
      userNote: 'Synthetic conflict note.',
      expectedVersion: duplicateSupport.version,
    },
    'PUT',
  );
  currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(assessmentFor(currentAnalysis, required)).toMatchObject({
    assessment: 'CONFLICTING_EVIDENCE',
    weightedContribution: 0,
  });
  expect(
    currentAnalysis.contradictions.some(
      (finding) => finding.findingType === 'CONFLICTING_EVIDENCE',
    ),
  ).toBeTruthy();
  await memberPage.reload();
  await expect(
    memberPage
      .locator('.findings-grid')
      .getByText(/both supporting and contradicting evidence are linked/i),
  ).toBeVisible();
  await expect(memberPage.getByText(/contradictions are reported explicitly/i)).toBeVisible();
  await expect(memberPage.getByText(/negative points/i)).toBeVisible();

  const loneContradiction = await createRequirement(
    memberPage,
    job.id,
    snapshot.id,
    'Synthetic contradiction-only requirement',
    'PREFERRED',
    'CONFIRMED',
    'DOMAIN_KNOWLEDGE',
  );
  await createLink(memberPage, loneContradiction, confirmedFact.id, 'CONTRADICTS');
  currentAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect(assessmentFor(currentAnalysis, loneContradiction)).toMatchObject({
    assessment: 'CONTRADICTED',
    weightedContribution: 0,
  });
  expect(
    currentAnalysis.contradictions.some(
      (finding) => finding.findingType === 'CONTRADICTING_EVIDENCE',
    ),
  ).toBeTruthy();

  const latestPreferred = (
    await apiJson<Requirement[]>(
      memberPage,
      `/api/jobs/${job.id}/snapshots/${snapshot.id}/requirements?limit=100`,
    )
  ).find((item) => item.id === preferred.id)!;
  preferred = await updateRequirement(memberPage, latestPreferred, {
    category: 'EXPERIENCE',
    importance: 'UNSPECIFIED',
    requirementText: 'Synthetic preferred edited with version',
  });
  expect(preferred.version).toBeGreaterThan(latestPreferred.version);

  const newerSnapshot = await apiWrite<Snapshot>(memberPage, `/api/jobs/${job.id}/snapshots`, {
    sourceType: 'PASTED_DESCRIPTION',
    descriptionText: 'Synthetic newer snapshot does not absorb older requirements.',
  });
  expect(
    await apiJson<Requirement[]>(
      memberPage,
      `/api/jobs/${job.id}/snapshots/${newerSnapshot.id}/requirements?limit=100`,
    ),
  ).toHaveLength(0);
  await openFit(memberPage, job, snapshot);
  await expect(requirementCard(memberPage, 'CONFIRMED', required.requirementText)).toBeVisible();

  memberPage.once('dialog', (dialog) => dialog.dismiss());
  await memberPage
    .locator('.fact-list')
    .getByRole('listitem')
    .filter({ hasText: deletable.requirementText })
    .getByRole('button', { name: 'Delete' })
    .click();
  await expect(requirementCard(memberPage, 'DRAFT', deletable.requirementText)).toBeVisible();
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage
    .locator('.fact-list')
    .getByRole('listitem')
    .filter({ hasText: deletable.requirementText })
    .getByRole('button', { name: 'Delete' })
    .click();
  await expect(memberPage.getByText('Requirement deleted.')).toBeVisible();
  memberPage.once('dialog', (dialog) => dialog.accept());
  await memberPage
    .getByRole('listitem')
    .filter({ hasText: required.requirementText })
    .getByRole('button', { name: 'Delete' })
    .click();
  await expect(memberPage.getByRole('alert')).toContainText('still has linked evidence');

  await memberPage
    .getByRole('listitem')
    .filter({ hasText: required.requirementText })
    .getByRole('button', { name: 'Link evidence' })
    .click();
  const linkForm = memberPage.getByRole('form', { name: 'Link eligible evidence' });
  await expect(linkForm).toBeVisible();
  await expect(linkForm.getByRole('group', { name: 'Evidence relationship' })).toBeVisible();
  await expect(linkForm.getByText(/does not mean you do not possess it/i)).toBeVisible();
  const radios = await linkForm.getByRole('radio').all();
  for (const radio of radios) await expect(radio).not.toBeChecked();
  await linkForm.getByLabel('Evidence source').selectOption('CAREER_FACT');
  await expect(
    linkForm
      .getByLabel('Eligible evidence candidates')
      .getByText('Skill: Synthetic confirmed evidence for deterministic fit.'),
  ).toBeVisible();
  await expect(
    linkForm.getByLabel('Eligible evidence candidates').getByText('Synthetic draft fact'),
  ).toHaveCount(0);
  await expect(linkForm.getByRole('option', { name: /Synthetic draft fact/i })).toHaveCount(0);
  await linkForm.locator('select').nth(1).selectOption(confirmedFact.id);
  await linkForm.getByRole('radio').first().check();
  await linkForm.getByLabel('User note').fill('Synthetic duplicate note retained.');
  await linkForm.getByRole('button', { name: 'Save evidence link' }).click();
  await expect(memberPage.getByRole('alert')).toContainText('conflicts');
  await expect(linkForm.getByLabel('User note')).toHaveValue('Synthetic duplicate note retained.');
  await linkForm.getByRole('button', { name: 'Cancel' }).click();

  const requiredEvidenceList = requirementCard(memberPage, 'CONFIRMED', required.requirementText)
    .getByLabel('Linked evidence')
    .getByRole('listitem')
    .filter({ hasText: 'Synthetic conflict note.' });
  await requiredEvidenceList.getByRole('button', { name: 'Edit link' }).click();
  const editLinkForm = memberPage.getByRole('form', { name: 'Edit evidence relationship' });
  await editLinkForm.getByLabel('User note').fill('Synthetic unsaved link note');
  await editLinkForm.getByRole('button', { name: 'Cancel' }).click();
  await expect(requiredEvidenceList.getByText('Synthetic conflict note.')).toBeVisible();
  memberPage.once('dialog', (dialog) => dialog.dismiss());
  await requiredEvidenceList.getByRole('button', { name: 'Remove link' }).click();
  await expect(requiredEvidenceList.getByText('Synthetic conflict note.')).toBeVisible();

  await expect(memberPage.getByRole('progressbar', { name: 'Evidence support' })).toBeVisible();
  await expect(memberPage.getByRole('progressbar', { name: 'Review coverage' })).toBeVisible();
  const fitWorkspace = memberPage.locator('.fit-workspace');
  for (const claim of [
    /hiring probability/i,
    /chance of getting hired/i,
    /qualified\/unqualified/i,
    /candidate quality/i,
    /recruiter score/i,
    /guaranteed match/i,
    /good\/bad candidate/i,
    /you should not apply/i,
    /you lack this skill/i,
  ]) {
    await expect(fitWorkspace.getByText(claim)).toHaveCount(0);
  }

  const sameAnalysis = await analysis(memberPage, job.id, snapshot.id);
  await memberPage.getByRole('button', { name: 'Refresh analysis' }).click();
  const refreshedAnalysis = await analysis(memberPage, job.id, snapshot.id);
  expect({
    status: refreshedAnalysis.analysisStatus,
    support: refreshedAnalysis.evidenceSupportScore,
    coverage: refreshedAnalysis.evidenceCoverageScore,
    confirmed: refreshedAnalysis.confirmedRequirementCount,
    assessments: refreshedAnalysis.requirementAssessments.map((item) => ({
      id: item.requirementId,
      assessment: item.assessment,
      contribution: item.weightedContribution,
    })),
  }).toEqual({
    status: sameAnalysis.analysisStatus,
    support: sameAnalysis.evidenceSupportScore,
    coverage: sameAnalysis.evidenceCoverageScore,
    confirmed: sameAnalysis.confirmedRequirementCount,
    assessments: sameAnalysis.requirementAssessments.map((item) => ({
      id: item.requirementId,
      assessment: item.assessment,
      contribution: item.weightedContribution,
    })),
  });

  const otherContext = await browser.newContext();
  const otherPage = await otherContext.newPage();
  await login(otherPage, otherLogin, memberPassword);
  const { confirmedFact: otherFact } = await createProfileAndFacts(otherPage);
  const otherCaptured = await captureJob(
    otherPage,
    'Phase5E Other Synthetic Company',
    'Synthetic other owner description.',
  );
  const otherRequirement = await createRequirement(
    otherPage,
    otherCaptured.job.id,
    otherCaptured.initialSnapshot!.id,
    'Synthetic other owner requirement',
    'REQUIRED',
    'CONFIRMED',
  );
  const otherLink = await createLink(otherPage, otherRequirement, otherFact.id, 'SUPPORTS');
  await openFit(otherPage, otherCaptured.job, otherCaptured.initialSnapshot!);
  await expect(otherPage.getByText(required.requirementText)).toHaveCount(0);

  const missingShape = await apiStatusAndShape(
    memberPage,
    '/api/job-requirements/11111111-2222-4333-8444-555555555555',
  );
  expect(await apiStatusAndShape(otherPage, `/api/job-requirements/${required.id}`)).toEqual(
    missingShape,
  );
  expect(await apiStatusAndShape(adminPage, `/api/job-requirements/${required.id}`)).toEqual(
    missingShape,
  );
  expect(
    (
      await apiStatusWithCsrf(
        adminPage,
        `/api/job-requirements/${required.id}`,
        {
          ...required,
          requirementText: 'Synthetic admin overwrite attempt',
          expectedVersion: required.version,
        },
        'PUT',
      )
    ).status,
  ).toBe(404);
  expect(
    (
      await apiStatusWithCsrf(
        adminPage,
        `/api/job-requirements/${required.id}`,
        {
          expectedVersion: required.version,
        },
        'DELETE',
      )
    ).status,
  ).toBe(404);
  expect(
    (await apiStatusAndShape(adminPage, `/api/job-requirements/${required.id}/evidence-links`))
      .status,
  ).toBe(404);
  expect(
    (
      await apiStatusWithCsrf(adminPage, `/api/job-requirements/${required.id}/evidence-links`, {
        evidenceType: 'CAREER_FACT',
        evidenceId: confirmedFact.id,
        relationship: 'SUPPORTS',
      })
    ).status,
  ).toBe(404);
  expect(
    (
      await apiStatusWithCsrf(
        adminPage,
        `/api/job-requirement-evidence/${otherLink.id}`,
        {
          evidenceType: 'CAREER_FACT',
          evidenceId: otherFact.id,
          relationship: 'CONTRADICTS',
          expectedVersion: otherLink.version,
        },
        'PUT',
      )
    ).status,
  ).toBe(404);
  expect(
    (
      await apiStatusWithCsrf(
        adminPage,
        `/api/job-requirement-evidence/${otherLink.id}`,
        {
          expectedVersion: otherLink.version,
        },
        'DELETE',
      )
    ).status,
  ).toBe(404);
  expect(
    (
      await apiStatusAndShape(
        adminPage,
        `/api/jobs/${job.id}/snapshots/${snapshot.id}/fit-analysis`,
      )
    ).status,
  ).toBe(404);
  expect(
    (
      await apiStatusAndShape(
        memberPage,
        `/api/jobs/${job.id}/snapshots/${otherCaptured.initialSnapshot!.id}/fit-analysis`,
      )
    ).status,
  ).toBe(404);
  expect(
    (
      await apiStatusWithCsrf(memberPage, `/api/job-requirements/${required.id}/evidence-links`, {
        evidenceType: 'CAREER_FACT',
        evidenceId: otherFact.id,
        relationship: 'SUPPORTS',
      })
    ).status,
  ).toBe(404);
  expect((await analysis(memberPage, job.id, snapshot.id)).requirementAssessments).toHaveLength(
    refreshedAnalysis.requirementAssessments.length,
  );

  const conflictContext = await browser.newContext();
  const conflictPage = await conflictContext.newPage();
  await login(conflictPage, memberLogin, memberPassword);
  await openFit(conflictPage, job, snapshot);
  await openFit(memberPage, job, snapshot);
  await memberPage
    .getByRole('listitem')
    .filter({ hasText: rejected.requirementText })
    .getByRole('button', { name: 'Edit' })
    .click();
  await conflictPage
    .getByRole('listitem')
    .filter({ hasText: rejected.requirementText })
    .getByRole('button', { name: 'Edit' })
    .click();
  await memberPage
    .getByRole('form', { name: 'Edit requirement' })
    .getByLabel('Requirement text')
    .fill('Synthetic committed requirement edit');
  await memberPage
    .getByRole('form', { name: 'Edit requirement' })
    .getByRole('button', { name: 'Save requirement' })
    .click();
  await expect(memberPage.getByText('Requirement saved.')).toBeVisible();
  await conflictPage
    .getByRole('form', { name: 'Edit requirement' })
    .getByLabel('Requirement text')
    .fill('Synthetic stale requirement edit');
  await conflictPage
    .getByRole('form', { name: 'Edit requirement' })
    .getByRole('button', { name: 'Save requirement' })
    .click();
  await expect(conflictPage.getByRole('alert')).toContainText('changed elsewhere');
  await expect(
    conflictPage.getByRole('form', { name: 'Edit requirement' }).getByLabel('Requirement text'),
  ).toHaveValue('Synthetic stale requirement edit');
  await conflictPage.getByRole('button', { name: 'Reload latest' }).click();
  await expect(conflictPage.getByText('Synthetic committed requirement edit')).toBeVisible();

  const staleLink = (
    await apiJson<EvidenceLink[]>(
      memberPage,
      `/api/job-requirements/${required.id}/evidence-links?limit=100`,
    )
  )[0]!;
  expect(
    (
      await apiStatusWithCsrf(
        conflictPage,
        `/api/job-requirement-evidence/${staleLink.id}`,
        {
          evidenceType: staleLink.evidenceType,
          evidenceId: staleLink.evidenceId,
          relationship: 'NOT_DEMONSTRATED',
          userNote: 'Synthetic stale direct link mutation',
          expectedVersion: 0,
        },
        'PUT',
      )
    ).status,
  ).toBe(409);

  for (const [path, method, body] of [
    [
      `/api/jobs/${job.id}/snapshots/${snapshot.id}/requirements`,
      'POST',
      { category: 'SKILL', importance: 'REQUIRED', requirementText: 'No csrf', status: 'DRAFT' },
    ],
    [
      `/api/job-requirements/${required.id}`,
      'PUT',
      { ...required, expectedVersion: required.version },
    ],
    [`/api/job-requirements/${required.id}`, 'DELETE', { expectedVersion: required.version }],
    [
      `/api/job-requirements/${required.id}/evidence-links`,
      'POST',
      { evidenceType: 'CAREER_FACT', evidenceId: confirmedFact.id, relationship: 'SUPPORTS' },
    ],
    [
      `/api/job-requirement-evidence/${staleLink.id}`,
      'PUT',
      {
        evidenceType: staleLink.evidenceType,
        evidenceId: staleLink.evidenceId,
        relationship: staleLink.relationship,
        expectedVersion: staleLink.version,
      },
    ],
    [
      `/api/job-requirement-evidence/${staleLink.id}`,
      'DELETE',
      { expectedVersion: staleLink.version },
    ],
  ] as const) {
    expect(
      (
        await apiStatusAndShape(memberPage, path, {
          method,
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
        })
      ).status,
    ).toBe(403);
  }
  expect(
    (
      await apiStatusAndShape(
        memberPage,
        `/api/jobs/${job.id}/snapshots/${snapshot.id}/fit-analysis`,
      )
    ).status,
  ).toBe(200);

  await memberPage.setViewportSize({ width: 390, height: 844 });
  await memberPage.emulateMedia({ reducedMotion: 'reduce' });
  await openFit(memberPage, job, snapshot);
  await expect(memberPage.getByRole('heading', { level: 1 })).toHaveCount(1);
  await expect(memberPage.getByRole('button', { name: 'Refresh analysis' })).toBeVisible();
  const pageOverflow = await memberPage.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
  expect(pageOverflow).toBeFalsy();
  await memberPage.keyboard.press('Tab');
  await expect(memberPage.locator(':focus')).toBeVisible();
  await expect(
    memberPage
      .locator('.assessment-list')
      .getByText(
        /Demonstrated by linked evidence|Partially demonstrated|Not demonstrated by linked evidence|Supporting and contradicting evidence linked/,
      )
      .first(),
  ).toBeVisible();

  await assertNoBrowserPersistence(memberPage, [
    required.requirementText,
    'Synthetic supports note.',
    'Synthetic conflict note.',
    confirmedFact.id,
    draftFact.id,
  ]);
  await assertNoBrowserPersistence(otherPage, [otherRequirement.requirementText, otherLink.id]);
  await assertNoBrowserPersistence(adminPage, [required.requirementText, confirmedFact.id]);
  expect(
    [...contactedHosts].every((host) => host === '127.0.0.1:5173' || host === '127.0.0.1:8080'),
  ).toBeTruthy();

  await anonymousPage.context().close();
  await memberContext.close();
  await otherContext.close();
  await conflictContext.close();
  await adminPage.context().close();
});
