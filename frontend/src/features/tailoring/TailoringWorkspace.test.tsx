import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TailoringWorkspace } from './TailoringWorkspace';
import { tailoringApi, type Proposal, type ProposalReview } from '../../api/tailoring';
import { profileApi, type CareerFact } from '../../api/profile';
import { documentsApi } from '../../api/documents';
import { ApiError } from '../../api/client';

vi.mock('../../api/tailoring', async (original) => ({
  ...(await original<typeof import('../../api/tailoring')>()),
  tailoringApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
    review: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
  },
}));
vi.mock('../../api/profile', () => ({ profileApi: { listFacts: vi.fn(), getFact: vi.fn() } }));
vi.mock('../../api/documents', () => ({ documentsApi: { getBaseResume: vi.fn() } }));
const proposal: Proposal = {
  id: 'p1',
  targetSection: 'SUMMARY',
  targetReference: 'Summary one',
  originalText: '<script>original</script>',
  proposedText: 'Manual wording',
  evidence: [{ careerFactId: 'f1', userNote: null }],
  sourceResume: { documentId: 'r1', version: 3, sha256Checksum: 'a'.repeat(64) },
  version: 7,
  lifecycleStatus: 'DRAFT',
  evidenceState: 'SUPPORTED_BY_CONFIRMED_FACTS',
  originalTextSource: 'USER_SUPPLIED',
  originalTextVerification: 'NOT_CHECKED_AGAINST_DOCUMENT',
  createdAt: '',
  updatedAt: '',
};
const fact: CareerFact = {
  id: 'f1',
  category: 'SKILL',
  status: 'CONFIRMED',
  factualContent: 'Confirmed experience',
  ongoing: false,
  createdAt: '',
  updatedAt: '',
  version: 2,
};
const review: ProposalReview = {
  proposal,
  eligibility: { eligible: true, reasons: [] },
  reviewRevision: 'exact-review-token',
  evidenceReferences: [{ careerFactId: 'f1', version: 2 }],
  originalTextNotice: 'Original text is user-supplied and not checked against the document.',
  evidenceNotice: 'Linked facts do not prove every claim.',
  approvalNotice: 'Approval is attestation, not export readiness.',
};
const expired = vi.fn();
async function open() {
  render(<TailoringWorkspace onExpired={expired} />);
  fireEvent.click(await screen.findByRole('button', { name: /Summary one/ }));
  await screen.findByDisplayValue('Manual wording');
}
async function reviewAndAttest() {
  fireEvent.click(screen.getByRole('button', { name: 'Load fresh review' }));
  const checkbox = await screen.findByRole('checkbox', { name: /I reviewed/ });
  expect(checkbox).not.toBeChecked();
  expect(screen.getByRole('button', { name: 'Approve proposal' })).toBeDisabled();
  fireEvent.click(checkbox);
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(tailoringApi.list).mockResolvedValue([proposal]);
  vi.mocked(tailoringApi.get).mockResolvedValue(proposal);
  vi.mocked(tailoringApi.review).mockResolvedValue(review);
  vi.mocked(profileApi.listFacts).mockResolvedValue([fact]);
  vi.mocked(profileApi.getFact).mockResolvedValue(fact);
  vi.mocked(documentsApi.getBaseResume).mockResolvedValue({
    id: 'r1',
    version: 3,
    sha256Checksum: 'a'.repeat(64),
    originalFilename: 'resume.pdf',
    mediaType: 'application/pdf',
    byteSize: 1,
    createdAt: '',
    updatedAt: '',
  });
});
describe('tailoring review workspace', () => {
  it('refreshes source attribution after failed creation without losing manual input', async () => {
    vi.mocked(tailoringApi.create).mockRejectedValue(new ApiError(404));
    render(<TailoringWorkspace onExpired={expired} />);
    fireEvent.click(screen.getByRole('button', { name: 'New proposal' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled());
    fireEvent.change(screen.getByLabelText('Target reference'), { target: { value: 'My target' } });
    fireEvent.change(screen.getByLabelText('Proposed text'), {
      target: { value: 'Unsaved creation' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Retry proposal loading' }));
    await waitFor(() => expect(documentsApi.getBaseResume).toHaveBeenCalledTimes(2));
    expect(screen.getByLabelText('Proposed text')).toHaveValue('Unsaved creation');
  });
  it('surfaces bounded-list overflow without claiming an empty list', async () => {
    vi.mocked(tailoringApi.list).mockRejectedValue(new ApiError(409, 'tailoring_too_large'));
    render(<TailoringWorkspace onExpired={expired} />);
    await screen.findByText(/exceeds the 100-item limit/);
    expect(screen.queryByText('No tailoring proposals yet.')).not.toBeInTheDocument();
  });
  it('shows source replacement as an approval blocker', async () => {
    vi.mocked(tailoringApi.review).mockResolvedValue({
      ...review,
      eligibility: { eligible: false, reasons: ['source_resume_changed'] },
    });
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Load fresh review' }));
    await screen.findByText(/source resume has changed/);
    expect(screen.getByRole('checkbox', { name: /I reviewed/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Approve proposal' })).toBeDisabled();
  });
  it('clears review when evidence changes and saves the new draft version', async () => {
    vi.mocked(tailoringApi.update).mockResolvedValue({
      ...proposal,
      version: 8,
      evidence: [],
      evidenceState: 'MISSING_EVIDENCE',
    });
    await open();
    await reviewAndAttest();
    fireEvent.click(screen.getByRole('checkbox', { name: /Confirmed experience/ }));
    expect(screen.queryByRole('checkbox', { name: /I reviewed/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await screen.findByText(/Draft saved/);
    expect(tailoringApi.update).toHaveBeenCalledWith(
      'p1',
      expect.objectContaining({ evidence: [] }),
      7,
    );
  });
  it('keeps the decision unchanged after rejection conflict', async () => {
    vi.mocked(tailoringApi.reject).mockRejectedValue(new ApiError(409, 'stale_version'));
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Reject proposal' }));
    await screen.findByRole('alert');
    expect(screen.getByText('Decision: Draft')).toBeInTheDocument();
    expect(screen.queryByText(/Proposal rejected/)).not.toBeInTheDocument();
  });
  it('discards an outstanding review when another proposal is selected', async () => {
    let resolveReview!: (value: ProposalReview) => void;
    vi.mocked(tailoringApi.list).mockResolvedValue([
      proposal,
      { ...proposal, id: 'p2', targetReference: 'Second' },
    ]);
    vi.mocked(tailoringApi.review).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveReview = resolve;
        }),
    );
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Load fresh review' }));
    fireEvent.click(screen.getByRole('button', { name: /Second/ }));
    await waitFor(() => expect(tailoringApi.get).toHaveBeenCalledWith('p2'));
    await act(async () => resolveReview(review));
    expect(screen.queryByRole('checkbox', { name: /I reviewed/ })).not.toBeInTheDocument();
  });
  it('creates manually with explicit confirmed evidence and pinned source', async () => {
    vi.mocked(tailoringApi.list).mockResolvedValue([]);
    vi.mocked(tailoringApi.create).mockResolvedValue(proposal);
    render(<TailoringWorkspace onExpired={expired} />);
    expect(await screen.findByText('No tailoring proposals yet.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'New proposal' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled());
    fireEvent.change(screen.getByLabelText('Target reference'), {
      target: { value: 'Summary one' },
    });
    fireEvent.change(screen.getByLabelText('Proposed text'), {
      target: { value: 'Manual wording' },
    });
    expect(screen.getByText(/Missing supporting evidence/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('checkbox', { name: /Confirmed experience/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() =>
      expect(tailoringApi.create).toHaveBeenCalledWith(
        expect.objectContaining({
          proposedText: 'Manual wording',
          evidence: [{ careerFactId: 'f1', userNote: null }],
        }),
        proposal.sourceResume,
      ),
    );
  });
  it('requires unchecked attestation and sends the exact reviewed revision only after deliberate approval', async () => {
    vi.mocked(tailoringApi.approve).mockResolvedValue({
      id: 'd1',
      proposalId: 'p1',
      decisionType: 'APPROVED',
      proposalVersion: 7,
      sourceResume: proposal.sourceResume,
      decidedAt: '',
      evidenceReferences: [],
    });
    await open();
    await reviewAndAttest();
    expect(screen.getAllByText('<script>original</script>')).toHaveLength(2);
    expect(document.querySelector('script')).toBeNull();
    expect(document.body.textContent).not.toContain('exact-review-token');
    fireEvent.click(screen.getByRole('button', { name: 'Approve proposal' }));
    await waitFor(() =>
      expect(tailoringApi.approve).toHaveBeenCalledWith('p1', 7, 'exact-review-token'),
    );
    expect(await screen.findByText(/Approval attestation recorded/)).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /I reviewed/ })).not.toBeInTheDocument();
  });
  it.each([
    new ApiError(409, 'stale_review'),
    new ApiError(409, 'approval_ineligible'),
    new Error('private payload'),
  ])('never shows approval on failure and requires a fresh attestation', async (error) => {
    vi.mocked(tailoringApi.approve).mockRejectedValue(error);
    await open();
    await reviewAndAttest();
    fireEvent.click(screen.getByRole('button', { name: 'Approve proposal' }));
    await screen.findByRole('alert');
    expect(screen.queryByText(/Approval attestation recorded/)).not.toBeInTheDocument();
    expect(screen.getByText('Decision: Draft')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve proposal' })).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('private payload');
    await reviewAndAttest();
    expect(tailoringApi.approve).toHaveBeenCalledTimes(1);
  });
  it('invalidates review on edit and preserves unsaved input after stale save', async () => {
    vi.mocked(tailoringApi.update).mockRejectedValue(new ApiError(409, 'stale_version'));
    await open();
    await reviewAndAttest();
    fireEvent.change(screen.getByLabelText('Proposed text'), {
      target: { value: 'Unsaved wording' },
    });
    expect(screen.queryByRole('checkbox', { name: /I reviewed/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await screen.findByRole('alert');
    expect(screen.getByLabelText('Proposed text')).toHaveValue('Unsaved wording');
    expect(tailoringApi.update).toHaveBeenCalledWith(
      'p1',
      expect.objectContaining({ proposedText: 'Unsaved wording' }),
      7,
    );
  });
  it('confirms deletion and explains preserved decision history', async () => {
    vi.mocked(tailoringApi.remove).mockRejectedValue(new ApiError(409, 'decision_history_exists'));
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Delete proposal' }));
    expect(tailoringApi.remove).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }));
    await screen.findByText(/Deletion refused/);
    expect(tailoringApi.remove).toHaveBeenCalledWith('p1', 7);
  });
  it('deletes successfully and rejects only after server success', async () => {
    vi.mocked(tailoringApi.reject).mockResolvedValue({
      id: 'd1',
      proposalId: 'p1',
      decisionType: 'REJECTED',
      proposalVersion: 7,
      sourceResume: proposal.sourceResume,
      decidedAt: '',
      evidenceReferences: [],
    });
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Reject proposal' }));
    await screen.findByText(/Proposal rejected/);
    expect(tailoringApi.reject).toHaveBeenCalledWith('p1', 7);
    fireEvent.click(screen.getByRole('button', { name: 'Delete proposal' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm deletion' }));
    await waitFor(() =>
      expect(screen.queryByRole('form', { name: 'Tailoring proposal' })).not.toBeInTheDocument(),
    );
  });
  it('expires the session on approval 401', async () => {
    vi.mocked(tailoringApi.approve).mockRejectedValue(new ApiError(401));
    await open();
    await reviewAndAttest();
    fireEvent.click(screen.getByRole('button', { name: 'Approve proposal' }));
    await waitFor(() => expect(expired).toHaveBeenCalledOnce());
  });
  it('ignores a detail response from a previous selection', async () => {
    let resolveOld!: (value: Proposal) => void;
    vi.mocked(tailoringApi.list).mockResolvedValue([
      proposal,
      { ...proposal, id: 'p2', targetReference: 'Second' },
    ]);
    vi.mocked(tailoringApi.get).mockImplementation((id) =>
      id === 'p1'
        ? new Promise((resolve) => {
            resolveOld = resolve;
          })
        : Promise.resolve({ ...proposal, id: 'p2', proposedText: 'Second text' }),
    );
    render(<TailoringWorkspace onExpired={expired} />);
    fireEvent.click(await screen.findByRole('button', { name: /Summary one/ }));
    await waitFor(() => expect(tailoringApi.get).toHaveBeenCalledWith('p1'));
    fireEvent.click(screen.getByRole('button', { name: /Second/ }));
    await screen.findByDisplayValue('Second text');
    await act(async () => resolveOld(proposal));
    expect(screen.getByLabelText('Proposed text')).toHaveValue('Second text');
  });
  it('blocks review when linked fact version changed while loading', async () => {
    vi.mocked(profileApi.getFact).mockResolvedValue({ ...fact, version: 3 });
    await open();
    fireEvent.click(screen.getByRole('button', { name: 'Load fresh review' }));
    await screen.findByRole('alert');
    expect(screen.queryByRole('button', { name: 'Approve proposal' })).not.toBeInTheDocument();
  });
  it('distinguishes historical approval from validity and allows a fresh review after edits', async () => {
    vi.mocked(tailoringApi.get).mockResolvedValue({ ...proposal, lifecycleStatus: 'APPROVED' });
    vi.mocked(tailoringApi.review).mockResolvedValue({
      ...review,
      proposal: { ...proposal, lifecycleStatus: 'APPROVED' },
      eligibility: { eligible: false, reasons: ['approval_stale'] },
    });
    await open();
    expect(screen.getByText(/Saving edits invalidates current approval/)).toBeInTheDocument();
    await reviewAndAttest();
    expect(screen.getByText(/Historical approval is stale/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Approve proposal' })).toBeEnabled();
  });
});
