import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { ResolvedExportReview } from './ResolvedExportReview';
import { tailoringApi, type Proposal, type ResolvedReview } from '../../api/tailoring';
import { profileApi } from '../../api/profile';
import { ApiError } from '../../api/client';

vi.mock('../../api/tailoring', () => ({
  tailoringApi: { resolvedReview: vi.fn(), approveResolved: vi.fn(), export: vi.fn() },
}));
vi.mock('../../api/profile', () => ({ profileApi: { getFact: vi.fn() } }));
const proposal = {
  id: 'proposal',
  version: 4,
  proposedText: 'Approved replacement',
  lifecycleStatus: 'APPROVED',
  sourceResume: { documentId: 'source', version: 2, sha256Checksum: 'a'.repeat(64) },
} as Proposal;
const resolved: ResolvedReview = {
  review: {
    proposal,
    eligibility: { eligible: true, reasons: [] },
    evidenceReferences: [{ careerFactId: 'fact', version: 3 }],
    reviewRevision: 'wording-token',
    originalTextNotice: '',
    evidenceNotice: '',
    approvalNotice: '',
  },
  sourceText: '<actual source text>',
  targetPolicy: 'DOCX_WHOLE_PARAGRAPH_V1',
  documentPart: 'word/document.xml',
  bodyChildIndex: 1,
  paragraphSha256: 'b'.repeat(64),
  resolvedRevision: 'exact-target-token',
  exportApproved: false,
};
const onExpired = vi.fn();
const onApproved = vi.fn();
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(tailoringApi.resolvedReview).mockResolvedValue(resolved);
  vi.mocked(profileApi.getFact).mockResolvedValue({
    id: 'fact',
    version: 3,
    status: 'CONFIRMED',
    category: 'SKILL',
    factualContent: 'Confirmed synthetic fact',
    ongoing: false,
    createdAt: '',
    updatedAt: '',
  });
  vi.stubGlobal(
    'URL',
    Object.assign(URL, {
      createObjectURL: vi.fn(() => 'blob:synthetic'),
      revokeObjectURL: vi.fn(),
    }),
  );
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
function mount() {
  return render(
    <ResolvedExportReview
      proposal={proposal}
      disabled={false}
      onExpired={onExpired}
      onApproved={onApproved}
    />,
  );
}
async function load() {
  fireEvent.click(screen.getByRole('button', { name: 'Review actual DOCX target' }));
  await screen.findByText('<actual source text>');
}
it('shows extracted source and replacement, requires fresh unchecked target attestation, and never automatically downloads', async () => {
  mount();
  await load();
  expect(screen.getByText('Approved replacement')).toBeInTheDocument();
  const checkbox = screen.getByRole('checkbox', { name: /this exact source location/ });
  expect(checkbox).not.toBeChecked();
  expect(screen.getByRole('button', { name: 'Approve resolved change' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Download tailored DOCX' })).toBeDisabled();
  fireEvent.click(checkbox);
  fireEvent.click(screen.getByRole('button', { name: 'Approve resolved change' }));
  await waitFor(() => expect(onApproved).toHaveBeenCalledOnce());
  expect(tailoringApi.approveResolved).toHaveBeenCalledWith('proposal', 4, 'exact-target-token');
  expect(tailoringApi.export).not.toHaveBeenCalled();
  expect(document.body.textContent).not.toContain('exact-target-token');
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
});
it('downloads only after successful response, revokes the object URL, and does not claim the file was saved', async () => {
  vi.mocked(tailoringApi.resolvedReview).mockResolvedValue({ ...resolved, exportApproved: true });
  vi.mocked(tailoringApi.export).mockResolvedValue(new Blob(['synthetic docx']));
  mount();
  await load();
  expect(tailoringApi.export).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Download tailored DOCX' }));
  await screen.findByText(/browser controls whether the file is saved/);
  expect(tailoringApi.export).toHaveBeenCalledWith('proposal', 4, 'exact-target-token');
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:synthetic');
  expect(document.querySelector('a[download]')).toBeNull();
});
it.each([
  new ApiError(409, 'stale_review'),
  new ApiError(409, 'export_unsupported'),
  new Error('private diagnostic'),
])('failed export never triggers a download and clears the reviewed token', async (error) => {
  vi.mocked(tailoringApi.resolvedReview).mockResolvedValue({ ...resolved, exportApproved: true });
  vi.mocked(tailoringApi.export).mockRejectedValue(error);
  mount();
  await load();
  fireEvent.click(screen.getByRole('button', { name: 'Download tailored DOCX' }));
  await screen.findByRole('alert');
  expect(URL.createObjectURL).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: 'Download tailored DOCX' })).not.toBeInTheDocument();
  expect(document.body.textContent).not.toContain('private diagnostic');
  expect(screen.queryByText(/response received/)).not.toBeInTheDocument();
});
it('handles session expiry and never shows approval success on failure', async () => {
  vi.mocked(tailoringApi.approveResolved).mockRejectedValue(new ApiError(401));
  mount();
  await load();
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(screen.getByRole('button', { name: 'Approve resolved change' }));
  await waitFor(() => expect(onExpired).toHaveBeenCalledOnce());
  expect(onApproved).not.toHaveBeenCalled();
});
it('requires a fresh review and attestation after an approval conflict without retrying', async () => {
  vi.mocked(tailoringApi.approveResolved).mockRejectedValue(new ApiError(409, 'stale_review'));
  mount();
  await load();
  fireEvent.click(screen.getByRole('checkbox'));
  fireEvent.click(screen.getByRole('button', { name: 'Approve resolved change' }));
  await screen.findByRole('alert');
  expect(onApproved).not.toHaveBeenCalled();
  expect(tailoringApi.approveResolved).toHaveBeenCalledTimes(1);
  expect(tailoringApi.export).not.toHaveBeenCalled();
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  await load();
  expect(screen.getByRole('checkbox')).not.toBeChecked();
  expect(screen.getByRole('button', { name: 'Approve resolved change' })).toBeDisabled();
});
it('ignores outstanding downloads after selection unmount', async () => {
  let finish!: (value: Blob) => void;
  vi.mocked(tailoringApi.resolvedReview).mockResolvedValue({ ...resolved, exportApproved: true });
  vi.mocked(tailoringApi.export).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const view = mount();
  await load();
  fireEvent.click(screen.getByRole('button', { name: 'Download tailored DOCX' }));
  view.unmount();
  await act(async () => finish(new Blob(['synthetic'])));
  expect(URL.createObjectURL).not.toHaveBeenCalled();
});
it('refuses presentation when linked fact version changes during review', async () => {
  vi.mocked(profileApi.getFact).mockResolvedValue({
    id: 'fact',
    version: 4,
    status: 'CONFIRMED',
    category: 'SKILL',
    factualContent: 'Changed',
    ongoing: false,
    createdAt: '',
    updatedAt: '',
  });
  mount();
  fireEvent.click(screen.getByRole('button', { name: 'Review actual DOCX target' }));
  await screen.findByRole('alert');
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
});
