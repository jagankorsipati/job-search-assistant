import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { createHash, randomUUID } from 'node:crypto';
import { crc32, inflateRawSync } from 'node:zlib';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Proposal, ProposalReview, ResolvedReview } from '../src/api/tailoring';
import type { CareerFact } from '../src/api/profile';

const base = '/api/documents/resume-tailoring-proposals';
const mime = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const original = 'Synthetic paragraph for replacement.';
const replacement = 'Synthetic candidate completed a controlled document exercise.';
const w = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');

// Stored ZIP fixtures use standard CRC32; no private files or generated build output.
function fixture(repeated = false, mixed = false): Buffer {
  const paragraph = mixed
    ? '<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">Synthetic paragraph </w:t></w:r><w:r><w:t>for replacement.</w:t></w:r></w:p>'
    : `<w:p><w:r><w:t xml:space="preserve">${original}</w:t></w:r></w:p>`;
  const parts: Record<string, string> = {
    '[Content_Types].xml':
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    '_rels/.rels':
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="main" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
    'word/document.xml': `<w:document xmlns:w="${w}"><w:body>${paragraph}${repeated ? paragraph : ''}<w:p><w:r><w:t>Untouched sentinel</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr></w:body></w:document>`,
  };
  const locals: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const [name, text] of Object.entries(parts)) {
    const filename = Buffer.from(name);
    const data = Buffer.from(text);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc32(data), 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(filename.length, 26);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50);
    entry.writeUInt16LE(20, 4);
    entry.writeUInt16LE(20, 6);
    entry.writeUInt32LE(crc32(data), 16);
    entry.writeUInt32LE(data.length, 20);
    entry.writeUInt32LE(data.length, 24);
    entry.writeUInt16LE(filename.length, 28);
    entry.writeUInt32LE(offset, 42);
    locals.push(local, filename, data);
    central.push(entry, filename);
    offset += local.length + filename.length + data.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(central.length / 2, 8);
  end.writeUInt16LE(central.length / 2, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

function unpack(bytes: Buffer) {
  expect(bytes.length <= 5 * 1024 * 1024).toBe(true);
  const end = bytes.length - 22;
  expect(bytes.readUInt32LE(end)).toBe(0x06054b50);
  const count = bytes.readUInt16LE(end + 10);
  expect(count <= 512).toBe(true);
  let at = bytes.readUInt32LE(end + 16);
  const parts = new Map<string, Buffer>();
  for (let i = 0; i < count; i++) {
    expect(bytes.readUInt32LE(at)).toBe(0x02014b50);
    const length = bytes.readUInt32LE(at + 20);
    const nameLength = bytes.readUInt16LE(at + 28);
    const name = bytes.subarray(at + 46, at + 46 + nameLength).toString();
    const local = bytes.readUInt32LE(at + 42);
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    const compressed = bytes.subarray(start, start + length);
    const method = bytes.readUInt16LE(at + 10);
    expect([0, 8].includes(method)).toBe(true);
    const data =
      method === 8 ? inflateRawSync(compressed, { maxOutputLength: 8 * 1024 * 1024 }) : compressed;
    expect(crc32(data)).toBe(bytes.readUInt32LE(at + 16));
    expect(parts.has(name)).toBe(false);
    parts.set(name, data);
    at += 46 + nameLength + bytes.readUInt16LE(at + 30) + bytes.readUInt16LE(at + 32);
  }
  return parts;
}

async function api(page: Page, path: string, method = 'GET', body?: unknown, token = true) {
  return page.evaluate(
    async ({ path, method, body, token }) => {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (token && method !== 'GET') {
        const csrf = await fetch('/api/auth/csrf').then((r) => r.json());
        headers[csrf.headerName] = csrf.token;
      }
      const response = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      return {
        status: response.status,
        body: text ? JSON.parse(text) : null,
        cache: response.headers.get('cache-control'),
      };
    },
    { path, method, body, token },
  );
}

async function login(page: Page, name: string, password: string) {
  await page.goto('/login');
  const result = await page.evaluate(
    async ({ name, password }) => {
      const csrf = await fetch('/api/auth/csrf').then((response) => response.json());
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
        body: JSON.stringify({ loginName: name, password }),
      });
      let code = 'none';
      try {
        const body = (await response.json()) as { code?: unknown };
        if (typeof body.code === 'string' && /^[a-z0-9_-]{1,64}$/.test(body.code)) code = body.code;
      } catch {
        // Login diagnostics only need safe status/code.
      }
      return { status: response.status, code };
    },
    { name, password },
  );
  const summary = `Tailoring login response: HTTP ${result.status}, generic code ${result.code}`;
  console.info(summary);
  expect(result.status, summary).toBe(200);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Your job-search workspace.' })).toBeVisible();
}

for (const scenario of ['security', 'lifecycle', 'targets'] as const) {
  test(`tailoring ${scenario}: attestation, real download and privacy`, async ({
    browser,
    baseURL,
  }) => {
    const password = process.env.E2E_MEMBER_PASSWORD;
    const adminPassword = process.env.E2E_ADMIN_PASSWORD;
    if (!password || !adminPassword || !baseURL)
      throw new Error('Missing disposable test configuration');
    const contexts: BrowserContext[] = [];
    const directory = await mkdtemp(join(tmpdir(), 'jsa-tailoring-'));
    const downloaded: string[] = [];
    const external: string[] = [];
    const logs: string[] = [];
    const pages: Page[] = [];
    async function page() {
      const context = await browser.newContext();
      contexts.push(context);
      await context.route('**/*', async (route) => {
        if (new URL(route.request().url()).origin !== new URL(baseURL!).origin) {
          external.push('external request');
          await route.abort();
        } else await route.continue();
      });
      const result = await context.newPage();
      result.on('console', (message) => logs.push(message.text()));
      result.on('download', (download) => downloaded.push(download.suggestedFilename()));
      pages.push(result);
      return result;
    }
    try {
      const admin = await page();
      await login(admin, 'e2e.admin', adminPassword);
      const owner = await page();
      const other = await page();
      const names = [`tailor.${randomUUID().slice(0, 12)}`, `other.${randomUUID().slice(0, 12)}`];
      for (const [index, member] of [owner, other].entries()) {
        const invitationResponse = await api(admin, '/api/admin/invitations', 'POST', {});
        expect(invitationResponse.status).toBe(201);
        await member.goto('/login');
        expect(
          (
            await api(member, '/api/invitations/accept', 'POST', {
              token: invitationResponse.body.token,
              displayName: 'Synthetic tailoring member',
              loginName: names[index],
              password,
              confirmPassword: password,
            })
          ).status,
        ).toBe(201);
        await login(member, names[index], password);
        expect(
          (
            await api(member, '/api/profile', 'POST', {
              professionalDisplayName: 'Synthetic candidate',
            })
          ).status,
        ).toBe(201);
      }
      const factFields = { category: 'PROJECT', factualContent: replacement, ongoing: false };
      let fact = (await api(owner, '/api/profile/career-facts', 'POST', factFields))
        .body as CareerFact;
      fact = (
        await api(owner, `/api/profile/career-facts/${fact.id}/confirm`, 'POST', {
          expectedVersion: fact.version,
          confirmedAccurate: true,
        })
      ).body;
      const foreignFact = (await api(other, '/api/profile/career-facts', 'POST', factFields))
        .body as CareerFact;
      const source = fixture();
      const digest = hash(source);
      await owner.goto('/profile');
      await owner
        .getByLabel('Resume file')
        .setInputFiles({ name: 'synthetic.docx', mimeType: mime, buffer: source });
      await owner.getByRole('button', { name: 'Upload base resume', exact: true }).click();
      await expect(owner.getByRole('button', { name: 'Download base resume' })).toBeVisible();
      await owner.getByRole('link', { name: 'Review resume tailoring proposals' }).click();
      await owner.getByRole('button', { name: 'New proposal' }).click();
      const form = owner.getByRole('form', { name: 'Tailoring proposal' });
      await form.getByLabel('Target reference').fill('Synthetic paragraph');
      await form.getByLabel('Original text', { exact: false }).fill(original);
      await form.getByLabel(/^Proposed text/).fill(replacement);
      await form.getByRole('checkbox').check();
      await form.getByRole('button', { name: 'Save draft' }).click();
      await expect(
        owner
          .getByRole('navigation', { name: 'Tailoring proposals' })
          .getByRole('button', { name: 'Synthetic paragraph Draft' }),
      ).toBeVisible();
      let proposal = (await api(owner, base)).body[0] as Proposal;
      const path = `${base}/${proposal.id}`;
      proposal = (await api(owner, path)).body;
      expect(proposal.evidence.length).toBe(1);
      await owner.getByRole('button', { name: 'Load fresh review' }).click();
      let review = owner.getByRole('region', { name: 'Before and after review' });
      await expect(review.getByRole('checkbox')).not.toBeChecked();
      await expect(review.getByRole('button', { name: 'Approve proposal' })).toBeDisabled();
      await review.getByRole('checkbox').check();
      await review.getByRole('checkbox').uncheck();
      expect(downloaded.length).toBe(0);
      await review.getByRole('checkbox').check();
      await review.getByRole('button', { name: 'Approve proposal' }).click();
      await expect(
        owner.getByText(
          'Approval attestation recorded. Refresh review to evaluate its current validity.',
        ),
      ).toBeVisible();
      let resolved = (
        await api(owner, `${path}/resolved-review?expectedVersion=${proposal.version}`)
      ).body as ResolvedReview;
      expect(resolved.exportApproved).toBe(false);
      expect(
        (
          await api(owner, `${path}/export`, 'POST', {
            expectedVersion: proposal.version,
            resolvedRevision: resolved.resolvedRevision,
          })
        ).status,
      ).toBe(409);
      if (scenario === 'security') {
        const target = owner.getByRole('region', { name: 'DOCX source-target review' });
        await target.getByRole('button', { name: 'Review actual DOCX target' }).click();
        await expect(target.getByRole('checkbox')).not.toBeChecked();
        await expect(
          target.getByRole('button', { name: 'Approve resolved change' }),
        ).toBeDisabled();
        await expect(target.getByRole('button', { name: 'Download tailored DOCX' })).toBeDisabled();
        await target.getByRole('checkbox').check();
        await target.getByRole('button', { name: 'Approve resolved change' }).click();
        await expect(
          owner.getByText(
            'Resolved-target approval recorded. Review the actual target again before downloading.',
          ),
        ).toBeVisible();
        expect(downloaded.length).toBe(0);
        await target.getByRole('button', { name: 'Review actual DOCX target' }).click();
        await expect(
          target.getByText(
            'Resolved-target approval is current at this review. Download rechecks it.',
          ),
        ).toBeVisible();
        const responsePromise = owner.waitForResponse(
          (r) => new URL(r.url()).pathname === `${path}/export`,
        );
        const downloadPromise = owner.waitForEvent('download');
        await target.getByRole('button', { name: 'Download tailored DOCX' }).click();
        const response = await responsePromise;
        expect(response.status()).toBe(200);
        expect(response.headers()['content-type']).toBe(mime);
        expect(response.headers()['cache-control']).toContain('no-store');
        expect(response.headers()['x-content-type-options']).toBe('nosniff');
        const download = await downloadPromise;
        expect(download.suggestedFilename()).toBe('tailored-resume.docx');
        const file = join(directory, 'result.docx');
        try {
          await download.saveAs(file);
          const output = unpack(await readFile(file));
          const input = unpack(source);
          expect([...output.keys()].sort()).toEqual([...input.keys()].sort());
          for (const [name, bytes] of input) {
            if (name !== 'word/document.xml') expect(output.get(name)?.equals(bytes)).toBe(true);
          }
          const structure = await owner.evaluate(
            ({ before, after, original, replacement }) => {
              const parser = new DOMParser();
              const a = parser.parseFromString(before, 'application/xml');
              const b = parser.parseFromString(after, 'application/xml');
              const texts = [
                ...b.getElementsByTagNameNS(
                  'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
                  't',
                ),
              ];
              const changed = texts.filter((node) => node.textContent === replacement);
              if (changed.length !== 1) return false;
              changed[0].textContent = original;
              // Serializer-added namespace declarations do not change OOXML semantics.
              function shape(node: Element): unknown {
                return [
                  node.namespaceURI,
                  node.localName,
                  [...node.attributes]
                    .filter((a) => a.namespaceURI !== 'http://www.w3.org/2000/xmlns/')
                    .map((a) => [a.namespaceURI, a.localName, a.value])
                    .sort(),
                  [...node.children].map(shape),
                  node.children.length ? '' : node.textContent,
                ];
              }
              return (
                JSON.stringify(shape(a.documentElement)) ===
                JSON.stringify(shape(b.documentElement))
              );
            },
            {
              before: input.get('word/document.xml')!.toString(),
              after: output.get('word/document.xml')!.toString(),
              original,
              replacement,
            },
          );
          expect(structure).toBe(true);
        } finally {
          await download.delete();
          await rm(file, { force: true });
        }
        const stored = await owner.request.get('/api/documents/base-resume/download');
        expect(hash(await stored.body())).toBe(digest);
        expect(hash(source)).toBe(digest);
        resolved = (await api(owner, `${path}/resolved-review?expectedVersion=${proposal.version}`))
          .body;
      }
      const exportBody = {
        expectedVersion: proposal.version,
        resolvedRevision: resolved.resolvedRevision,
      };
      const fields = {
        targetSection: proposal.targetSection,
        targetReference: proposal.targetReference,
        originalText: original,
        proposedText: replacement,
        evidence: proposal.evidence,
      };
      if (scenario === 'security') {
        const missing = await api(owner, base, 'POST', {
          ...fields,
          evidence: [],
          sourceResumeDocumentId: proposal.sourceResume.documentId,
          sourceResumeVersion: proposal.sourceResume.version,
          sourceResumeSha256Checksum: proposal.sourceResume.sha256Checksum,
        });
        expect(missing.status).toBe(201);
        const missingPath = `${base}/${missing.body.id}`;
        expect((await api(owner, `${missingPath}/review`)).body.eligibility.eligible).toBe(false);
        expect(
          (await api(owner, missingPath, 'DELETE', { expectedVersion: missing.body.version }))
            .status,
        ).toBe(204);
        expect(
          (
            await api(owner, `${path}/approve-resolved`, 'POST', {
              ...exportBody,
              attestedTargetAndExperience: false,
            })
          ).status,
        ).toBe(409);
        for (const actor of [other, admin]) {
          expect(
            (
              await api(actor, base, 'POST', {
                ...fields,
                sourceResumeDocumentId: proposal.sourceResume.documentId,
                sourceResumeVersion: proposal.sourceResume.version,
                sourceResumeSha256Checksum: proposal.sourceResume.sha256Checksum,
              })
            ).status,
          ).toBe(404);
          expect((await api(actor, `${path}?ownerAccountId=${proposal.id}`)).status).toBe(404);
          for (const [method, suffix, body] of [
            ['GET', '', undefined],
            ['GET', '/review', undefined],
            ['GET', `/resolved-review?expectedVersion=${proposal.version}`, undefined],
            ['PUT', '', { ...proposal, expectedVersion: proposal.version }],
            ['DELETE', '', { expectedVersion: proposal.version }],
            [
              'POST',
              '/approve',
              {
                expectedVersion: proposal.version,
                reviewedRevision: resolved.review.reviewRevision,
                attestedExperienceAccurate: true,
              },
            ],
            ['POST', '/approve-resolved', { ...exportBody, attestedTargetAndExperience: true }],
            ['POST', '/reject', { expectedVersion: proposal.version }],
            ['POST', '/export', exportBody],
          ] as const) {
            const denied = await api(actor, `${path}${suffix}`, method, body);
            const absent = await api(actor, `${base}/${randomUUID()}${suffix}`, method, body);
            expect(denied.status).toBe(404);
            expect(JSON.stringify(denied.body) === JSON.stringify(absent.body)).toBe(true);
          }
        }
        expect(
          (
            await api(owner, path, 'PUT', {
              ...fields,
              expectedVersion: proposal.version,
              evidence: [{ careerFactId: foreignFact.id }],
            })
          ).status,
        ).toBe(404);
        for (const suffix of ['/approve', '/approve-resolved', '/reject', '/export']) {
          expect((await api(owner, `${path}${suffix}`, 'POST', exportBody, false)).status).toBe(
            403,
          );
        }
        expect(
          (await api(owner, path, 'DELETE', { expectedVersion: proposal.version }, false)).status,
        ).toBe(403);
        expect(
          (await api(owner, path, 'PUT', { ...fields, expectedVersion: proposal.version }, false))
            .status,
        ).toBe(403);
        expect((await api(owner, base, 'POST', {}, false)).status).toBe(403);
        const anonymous = await page();
        await anonymous.goto('/login');
        expect((await api(anonymous, path)).status).toBe(401);
        expect((await api(anonymous, `${path}/export`, 'POST', exportBody)).status).toBe(401);
      }

      if (scenario === 'lifecycle') {
        // An out-of-band owner write commits while the visible form holds unsaved wording.
        await expect(
          owner.getByRole('heading', { name: 'Resume tailoring proposals' }),
        ).toBeVisible();
        await owner.goto('/profile/tailoring');
        await owner
          .getByRole('navigation', { name: 'Tailoring proposals' })
          .getByRole('button', { name: 'Synthetic paragraph Approval recorded' })
          .click();
        await expect(form).toBeVisible();
        await form.getByLabel(/^Proposed text/).fill('Unsaved synthetic wording');
        proposal = (await api(owner, path, 'PUT', { ...fields, expectedVersion: proposal.version }))
          .body;
        await form.getByRole('button', { name: 'Save draft' }).click();
        await expect(owner.getByRole('alert')).toContainText('Unsaved input is preserved');
        await expect(form.getByLabel(/^Proposed text/)).toHaveValue('Unsaved synthetic wording');
        expect((await api(owner, `${path}/export`, 'POST', exportBody)).status).toBe(409);
        owner.once('dialog', (dialog) => dialog.accept());
        await owner.getByRole('button', { name: 'Reload saved proposal' }).click();
        await expect(form.getByLabel(/^Proposed text/)).toHaveValue(replacement);
        await owner.getByRole('button', { name: 'Load fresh review' }).click();
        review = owner.getByRole('region', { name: 'Before and after review' });
        await expect(review.getByRole('checkbox')).not.toBeChecked();
        await expect(review.getByRole('checkbox')).toBeEnabled();
        await review.getByRole('checkbox').check();
        const changedFact = await api(owner, `/api/profile/career-facts/${fact.id}`, 'PUT', {
          ...factFields,
          expectedVersion: fact.version,
        });
        expect(changedFact.status).toBe(200);
        fact = changedFact.body;
        // Public fact edits may return DRAFT; reconfirm before testing the version binding.
        if (fact.status !== 'CONFIRMED')
          fact = (
            await api(owner, `/api/profile/career-facts/${fact.id}/confirm`, 'POST', {
              expectedVersion: fact.version,
              confirmedAccurate: true,
            })
          ).body;
        await review.getByRole('button', { name: 'Approve proposal' }).click();
        await expect(owner.getByRole('alert')).toContainText('changed');
        expect(downloaded.length).toBe(0);
        await expect(
          owner.getByText(
            'Approval attestation recorded. Refresh review to evaluate its current validity.',
          ),
        ).toHaveCount(0);
        await owner.getByRole('button', { name: 'Load fresh review' }).click();
        await expect(review.getByRole('checkbox')).not.toBeChecked();
        expect(
          (await api(owner, `${path}/review`)).body.eligibility.reasons.every(
            (reason: string) => reason === 'approval_stale',
          ),
        ).toBe(true);
        await expect(review.getByRole('checkbox')).toBeEnabled();
        await review.getByRole('checkbox').check();
        await review.getByRole('button', { name: 'Approve proposal' }).click();
        await expect(
          owner.getByText(
            'Approval attestation recorded. Refresh review to evaluate its current validity.',
          ),
        ).toBeVisible();
        await owner.getByRole('button', { name: 'Delete proposal', exact: true }).click();
        await owner.getByRole('button', { name: 'Cancel deletion' }).click();
        expect((await api(owner, path)).status).toBe(200);
        await owner.getByRole('button', { name: 'Delete proposal', exact: true }).click();
        await owner.getByRole('button', { name: 'Confirm deletion' }).click();
        await expect(owner.getByRole('alert')).toContainText('Deletion refused');
        await owner.getByRole('button', { name: 'Reject proposal' }).click();
        await expect(
          owner.getByText('Proposal rejected. This is not a judgment of candidate qualifications.'),
        ).toBeVisible();
        const rejected = (await api(owner, `${path}/review`)).body as ProposalReview;
        expect(rejected.proposal.lifecycleStatus).toBe('REJECTED');
        expect((await api(owner, `${path}/export`, 'POST', exportBody)).status).toBe(409);
      }
      if (scenario === 'targets') {
        const metadata = (await api(owner, '/api/documents/base-resume')).body;
        const csrf = await owner.request.get('/api/auth/csrf').then((r) => r.json());
        const replaced = await owner.request.put('/api/documents/base-resume', {
          headers: { [csrf.headerName]: csrf.token },
          multipart: {
            expectedVersion: String(metadata.version),
            file: { name: 'repeated.docx', mimeType: mime, buffer: fixture(true) },
          },
        });
        expect(replaced.status()).toBe(200);
        expect((await api(owner, `${path}/export`, 'POST', exportBody)).status).toBe(409);
        expect(
          (await api(owner, `${path}/resolved-review?expectedVersion=${proposal.version}`)).status,
        ).toBe(409);
        const current = await replaced.json();
        const create = {
          ...fields,
          sourceResumeDocumentId: current.id,
          sourceResumeVersion: current.version,
          sourceResumeSha256Checksum: current.sha256Checksum,
        };
        for (const text of [original, 'Missing synthetic paragraph']) {
          const result = await api(owner, base, 'POST', { ...create, originalText: text });
          expect(result.status).toBe(201);
          const unsupported = result.body as Proposal;
          const resultPath = `${base}/${unsupported.id}`;
          const refused = await api(
            owner,
            `${resultPath}/resolved-review?expectedVersion=${unsupported.version}`,
          );
          expect(refused.status).toBe(409);
          expect(refused.cache).toContain('no-store');
          expect(
            (
              await api(owner, `${resultPath}/export`, 'POST', {
                expectedVersion: unsupported.version,
                resolvedRevision: '0'.repeat(64),
              })
            ).status,
          ).toBe(409);
          expect(
            (await api(owner, resultPath, 'DELETE', { expectedVersion: unsupported.version }))
              .status,
          ).toBe(204);
        }
        expect(downloaded.length).toBe(0);
        expect(
          hash(await (await owner.request.get('/api/documents/base-resume/download')).body()),
        ).toBe(hash(fixture(true)));
        const mixedResponse = await owner.request.put('/api/documents/base-resume', {
          headers: { [csrf.headerName]: csrf.token },
          multipart: {
            expectedVersion: String(current.version),
            file: { name: 'mixed.docx', mimeType: mime, buffer: fixture(false, true) },
          },
        });
        expect(mixedResponse.status()).toBe(200);
        const mixedSource = await mixedResponse.json();
        const mixedProposal = (
          await api(owner, base, 'POST', {
            ...create,
            sourceResumeVersion: mixedSource.version,
            sourceResumeSha256Checksum: mixedSource.sha256Checksum,
          })
        ).body as Proposal;
        expect(
          (
            await api(
              owner,
              `${base}/${mixedProposal.id}/resolved-review?expectedVersion=${mixedProposal.version}`,
            )
          ).status,
        ).toBe(409);
        expect(
          (
            await api(owner, `${base}/${mixedProposal.id}/export`, 'POST', {
              expectedVersion: mixedProposal.version,
              resolvedRevision: '0'.repeat(64),
            })
          ).status,
        ).toBe(409);
        expect(downloaded.length).toBe(0);
      }
      const privacy = await owner.evaluate(async () => ({
        local: localStorage.length,
        session: sessionStorage.length,
        databases: (await indexedDB.databases()).length,
        url: location.href,
        cookie: document.cookie,
      }));
      expect(privacy.local + privacy.session + privacy.databases).toBe(0);
      expect(
        [original, replacement, resolved.resolvedRevision].some(
          (value) =>
            JSON.stringify(privacy).includes(value) || logs.some((line) => line.includes(value)),
        ),
      ).toBe(false);
      expect(external.length).toBe(0);
      for (const width of [1280, 390]) {
        await owner.setViewportSize({ width, height: 844 });
        expect(await owner.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
          true,
        );
        await expect(
          owner.getByRole('heading', { name: 'Resume tailoring proposals' }),
        ).toBeVisible();
      }
      if (scenario === 'security') {
        await owner.context().clearCookies();
        await owner
          .getByRole('region', { name: 'DOCX source-target review' })
          .getByRole('button', { name: 'Review actual DOCX target' })
          .click();
        await expect(
          owner.getByRole('heading', { name: 'Sign in to your private workspace' }),
        ).toBeVisible();
        expect(downloaded.length).toBe(1);
      }
    } finally {
      await Promise.allSettled(contexts.map((context) => context.close()));
      await rm(directory, { recursive: true, force: true });
    }
  });
}
