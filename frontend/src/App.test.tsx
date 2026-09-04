import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ApiError, authApi } from './api/auth';
import { applicationsApi, type JobApplication } from './api/applications';
import { documentsApi, type BaseResumeMetadata } from './api/documents';
import { fitApi, type FitAnalysis, type JobRequirement } from './api/fit';
import { jobsApi, type CapturedJob, type JobDescriptionSnapshot } from './api/jobs';
import { profileApi, type CandidateProfile, type CareerFact } from './api/profile';

vi.mock('./api/auth', async (original) => {
  const actual = await original<typeof import('./api/auth')>();
  return {
    ...actual,
    authApi: {
      me: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
      createInvitation: vi.fn(),
      acceptInvitation: vi.fn(),
      listAccounts: vi.fn(),
      disableAccount: vi.fn(),
      reactivateAccount: vi.fn(),
    },
  };
});
vi.mock('./api/profile', async (original) => {
  const actual = await original<typeof import('./api/profile')>();
  return {
    ...actual,
    profileApi: {
      getProfile: vi.fn(),
      createProfile: vi.fn(),
      updateProfile: vi.fn(),
      listFacts: vi.fn(),
      createFact: vi.fn(),
      getFact: vi.fn(),
      updateFact: vi.fn(),
      confirmFact: vi.fn(),
      archiveFact: vi.fn(),
      restoreFact: vi.fn(),
    },
  };
});
vi.mock('./api/documents', async (original) => {
  const actual = await original<typeof import('./api/documents')>();
  return {
    ...actual,
    documentsApi: {
      getBaseResume: vi.fn(),
      uploadBaseResume: vi.fn(),
      replaceBaseResume: vi.fn(),
      downloadBaseResume: vi.fn(),
    },
  };
});
vi.mock('./api/jobs', async (original) => {
  const actual = await original<typeof import('./api/jobs')>();
  return {
    ...actual,
    jobsApi: {
      listJobs: vi.fn(),
      captureJob: vi.fn(),
      getJob: vi.fn(),
      updateJob: vi.fn(),
      archiveJob: vi.fn(),
      restoreJob: vi.fn(),
      listSnapshots: vi.fn(),
      getSnapshot: vi.fn(),
      appendSnapshot: vi.fn(),
    },
  };
});
vi.mock('./api/fit', async (original) => {
  const actual = await original<typeof import('./api/fit')>();
  return {
    ...actual,
    fitApi: {
      listRequirements: vi.fn(),
      createRequirement: vi.fn(),
      updateRequirement: vi.fn(),
      deleteRequirement: vi.fn(),
      listEvidenceLinks: vi.fn(),
      createEvidenceLink: vi.fn(),
      updateEvidenceLink: vi.fn(),
      deleteEvidenceLink: vi.fn(),
      getAnalysis: vi.fn(),
    },
  };
});
vi.mock('./api/applications', async (original) => {
  const actual = await original<typeof import('./api/applications')>();
  return {
    ...actual,
    applicationsApi: {
      listApplications: vi.fn(),
      createApplication: vi.fn(),
      getApplication: vi.fn(),
      updateApplication: vi.fn(),
      transitionApplication: vi.fn(),
      listHistory: vi.fn(),
      archiveApplication: vi.fn(),
      restoreApplication: vi.fn(),
    },
  };
});
const api = vi.mocked(authApi);
const profile = vi.mocked(profileApi);
const documents = vi.mocked(documentsApi);
const jobs = vi.mocked(jobsApi);
const applications = vi.mocked(applicationsApi);
const fit = vi.mocked(fitApi);

const savedProfile: CandidateProfile = {
  id: 'profile-id',
  professionalDisplayName: 'Ada Candidate',
  professionalHeadline: 'Senior builder',
  careerSummary: 'Builds useful systems.',
  locationPreference: 'Remote',
  targetRoles: 'Platform engineer',
  workAuthorizationStatement: 'Authorized to work.',
  workLocationPreferences: 'Remote first',
  createdAt: '2026-08-20T00:00:00Z',
  updatedAt: '2026-08-21T00:00:00Z',
  version: 3,
};

const draftFact: CareerFact = {
  id: 'fact-draft',
  category: 'EMPLOYMENT',
  status: 'DRAFT',
  factualContent: 'Led a migration project.',
  organization: 'Example Co',
  title: 'Engineer',
  location: 'Remote',
  startedOn: '2025-01-01',
  endedOn: null,
  ongoing: true,
  createdAt: '2026-08-20T00:00:00Z',
  updatedAt: '2026-08-21T00:00:00Z',
  version: 2,
};

const confirmedFact: CareerFact = {
  ...draftFact,
  id: 'fact-confirmed',
  category: 'SKILL',
  status: 'CONFIRMED',
  factualContent: 'Uses TypeScript professionally.',
  version: 5,
};

const archivedFact: CareerFact = {
  ...draftFact,
  id: 'fact-archived',
  status: 'ARCHIVED',
  factualContent: 'Older accomplishment.',
  version: 7,
};

const savedResume: BaseResumeMetadata = {
  id: 'resume-id',
  originalFilename: 'base-resume.pdf',
  mediaType: 'application/pdf',
  byteSize: 54,
  createdAt: '2026-08-20T00:00:00Z',
  updatedAt: '2026-08-21T00:00:00Z',
  version: 2,
};

const savedJob: CapturedJob = {
  id: 'job-id',
  companyName: 'Acme',
  jobTitle: 'Platform Engineer',
  workLocation: 'Remote',
  postingUrl: 'https://example.test/job',
  sourceType: 'MANUAL',
  employmentType: 'FULL_TIME',
  externalPostingId: null,
  datePosted: '2026-08-01',
  capturedAt: '2026-08-20T00:00:00Z',
  metadataUpdatedAt: '2026-08-21T00:00:00Z',
  version: 2,
  archivedAt: null,
  archived: false,
};

const archivedJob: CapturedJob = {
  ...savedJob,
  id: 'archived-job-id',
  companyName: 'Globex',
  jobTitle: 'Data Engineer',
  workLocation: 'Hybrid',
  postingUrl: 'https://globex.test/jobs/data',
  sourceType: 'URL_REFERENCE',
  employmentType: 'CONTRACT',
  externalPostingId: 'GLOBEX-77',
  archivedAt: '2026-08-22T00:00:00Z',
  archived: true,
};

const differentJob: CapturedJob = {
  ...savedJob,
  id: 'different-job-id',
  companyName: 'Initech',
  jobTitle: 'Support Specialist',
  workLocation: 'Austin',
  postingUrl: 'https://initech.test/jobs/support',
  sourceType: 'PASTED_DESCRIPTION',
  employmentType: 'PART_TIME',
  externalPostingId: 'INI-12',
};

const savedSnapshot: JobDescriptionSnapshot = {
  id: 'snapshot-id',
  jobId: 'job-id',
  sequence: 1,
  sourceType: 'PASTED_DESCRIPTION',
  descriptionText: 'Build reliable systems.',
  sha256Digest: 'digest',
  capturedAt: '2026-08-20T00:00:00Z',
};

const savedApplication: JobApplication = {
  id: 'application-id',
  jobId: 'job-id',
  status: 'READY_TO_APPLY',
  appliedAt: null,
  nextActionText: 'Submit application',
  nextActionDueDate: '2026-08-30',
  privateNotes: 'Use tailored resume.',
  statusChangedAt: '2026-08-21T00:00:00Z',
  createdAt: '2026-08-20T00:00:00Z',
  updatedAt: '2026-08-21T00:00:00Z',
  version: 3,
  archivedAt: null,
  archived: false,
};

const draftRequirement: JobRequirement = {
  id: 'requirement-draft',
  jobId: 'job-id',
  jobSnapshotId: 'snapshot-id',
  category: 'SKILL',
  importance: 'REQUIRED',
  requirementText: 'Use TypeScript',
  sourceExcerpt: 'TypeScript required',
  status: 'DRAFT',
  createdAt: '2026-08-20T00:00:00Z',
  updatedAt: '2026-08-21T00:00:00Z',
  version: 2,
};

const confirmedRequirement: JobRequirement = {
  ...draftRequirement,
  id: 'requirement-confirmed',
  status: 'CONFIRMED',
  requirementText: 'Build reliable systems',
  sourceExcerpt: 'Build reliable systems.',
  version: 5,
};

const rejectedRequirement: JobRequirement = {
  ...draftRequirement,
  id: 'requirement-rejected',
  status: 'REJECTED',
  requirementText: 'Own a flying car',
  sourceExcerpt: null,
  version: 1,
};

const scorableAnalysis: FitAnalysis = {
  policyVersion: 'DETERMINISTIC_FIT_V1',
  analysisStatus: 'SCORABLE',
  jobId: 'job-id',
  snapshotId: 'snapshot-id',
  evidenceSupportScore: 50,
  evidenceCoverageScore: 100,
  confirmedRequirementCount: 1,
  draftRequirementCount: 1,
  rejectedRequirementCount: 1,
  totalEligibleWeight: 2,
  supportPoints: 1,
  assessedWeight: 2,
  importanceBreakdowns: [
    {
      importance: 'REQUIRED',
      applicable: true,
      confirmedRequirementCount: 1,
      totalWeight: 2,
      assessedCount: 1,
      demonstratedCount: 0,
      partiallyDemonstratedCount: 1,
      notDemonstratedCount: 0,
      contradictedCount: 0,
      conflictingEvidenceCount: 0,
      unassessedCount: 0,
      evidenceSupportScore: 50,
      evidenceCoverageScore: 100,
    },
    {
      importance: 'PREFERRED',
      applicable: false,
      confirmedRequirementCount: 0,
      totalWeight: 0,
      assessedCount: 0,
      demonstratedCount: 0,
      partiallyDemonstratedCount: 0,
      notDemonstratedCount: 0,
      contradictedCount: 0,
      conflictingEvidenceCount: 0,
      unassessedCount: 0,
      evidenceSupportScore: null,
      evidenceCoverageScore: null,
    },
    {
      importance: 'UNSPECIFIED',
      applicable: false,
      confirmedRequirementCount: 0,
      totalWeight: 0,
      assessedCount: 0,
      demonstratedCount: 0,
      partiallyDemonstratedCount: 0,
      notDemonstratedCount: 0,
      contradictedCount: 0,
      conflictingEvidenceCount: 0,
      unassessedCount: 0,
      evidenceSupportScore: null,
      evidenceCoverageScore: null,
    },
  ],
  requirementAssessments: [
    {
      requirementId: 'requirement-confirmed',
      requirementCategory: 'RESPONSIBILITY',
      importance: 'REQUIRED',
      requirementStatus: 'CONFIRMED',
      requirementText: 'Build reliable systems',
      sourceExcerpt: 'Build reliable systems.',
      assessment: 'PARTIALLY_DEMONSTRATED',
      reasonCode: 'PARTIAL_EVIDENCE',
      requirementWeight: 2,
      evidenceCredit: 0.5,
      weightedContribution: 1,
      evidenceRelationshipCounts: {
        supportsCount: 0,
        partiallySupportsCount: 1,
        contradictsCount: 0,
        notDemonstratedCount: 0,
      },
      evidenceLinks: [
        {
          evidenceLinkId: 'link-1',
          evidenceType: 'CAREER_FACT',
          evidenceId: 'fact-confirmed',
          relationship: 'PARTIALLY_SUPPORTS',
          userNote: 'Some relevant work.',
          evidenceReference: 'CAREER_FACT',
        },
      ],
    },
  ],
  gaps: [
    {
      findingType: 'PARTIAL_EVIDENCE',
      requirementId: 'requirement-confirmed',
      requirementCategory: 'RESPONSIBILITY',
      importance: 'REQUIRED',
      assessment: 'PARTIALLY_DEMONSTRATED',
      reasonCode: 'PARTIAL_EVIDENCE',
    },
  ],
  contradictions: [
    {
      findingType: 'CONFLICTING_EVIDENCE',
      requirementId: 'requirement-confirmed',
      requirementCategory: 'RESPONSIBILITY',
      importance: 'REQUIRED',
      assessment: 'CONFLICTING_EVIDENCE',
      reasonCode: 'SUPPORTING_AND_CONTRADICTING_EVIDENCE',
    },
  ],
};

const nonScorableAnalysis: FitAnalysis = {
  ...scorableAnalysis,
  analysisStatus: 'NON_SCORABLE',
  evidenceSupportScore: null,
  evidenceCoverageScore: null,
  confirmedRequirementCount: 0,
  totalEligibleWeight: 0,
  supportPoints: 0,
  assessedWeight: 0,
  requirementAssessments: [],
  gaps: [],
  contradictions: [],
};

describe('authentication experience', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/');
    api.me.mockRejectedValue(new ApiError(401));
    api.logout.mockResolvedValue();
    profile.getProfile.mockResolvedValue(savedProfile);
    profile.listFacts.mockResolvedValue([]);
    profile.createProfile.mockResolvedValue(savedProfile);
    profile.updateProfile.mockResolvedValue(savedProfile);
    profile.createFact.mockResolvedValue(draftFact);
    profile.updateFact.mockResolvedValue(draftFact);
    profile.confirmFact.mockResolvedValue({ ...draftFact, status: 'CONFIRMED', version: 3 });
    profile.archiveFact.mockResolvedValue({ ...draftFact, status: 'ARCHIVED', version: 3 });
    profile.restoreFact.mockResolvedValue({ ...archivedFact, status: 'DRAFT', version: 8 });
    documents.getBaseResume.mockRejectedValue(new ApiError(404));
    documents.uploadBaseResume.mockResolvedValue(savedResume);
    documents.replaceBaseResume.mockResolvedValue({
      ...savedResume,
      originalFilename: 'updated.docx',
      version: 3,
    });
    documents.downloadBaseResume.mockResolvedValue(new Response(new Blob(['resume'])));
  });

  it('restores an authenticated admin session and exposes invitation controls', async () => {
    api.me.mockResolvedValue({ accountId: 'id', role: 'ADMIN' });
    render(<App />);
    expect(await screen.findByText('Signed in as admin')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create member invitation/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /manage household members/i })).toBeInTheDocument();
  });

  it('handles generic login failure, rate limiting, and success', async () => {
    api.login
      .mockRejectedValueOnce(new ApiError(401))
      .mockRejectedValueOnce(new ApiError(429, undefined, '30'))
      .mockResolvedValue({ accountId: 'id', role: 'MEMBER' });
    render(<App />);
    await screen.findByRole('heading', { name: /sign in/i });
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'member' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/incorrect/i);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/30 seconds/i);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Signed in as member')).toBeInTheDocument();
    expect(screen.queryByText(/create member invitation/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/manage household members/i)).not.toBeInTheDocument();
  });

  it('renders only approved account fields and confirms a server-backed disable', async () => {
    api.me.mockResolvedValue({ accountId: 'admin-id', role: 'ADMIN' });
    api.listAccounts.mockResolvedValue([
      {
        accountId: 'admin-id',
        loginName: 'admin',
        displayName: 'Administrator',
        role: 'ADMIN',
        status: 'ACTIVE',
        createdAt: '2026-08-15T00:00:00Z',
      },
      {
        accountId: 'member-id',
        loginName: 'member',
        displayName: 'Household Member',
        role: 'MEMBER',
        status: 'ACTIVE',
        createdAt: '2026-08-15T00:00:00Z',
      },
    ]);
    api.disableAccount.mockResolvedValue();
    api.reactivateAccount.mockResolvedValue();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /manage household members/i }));
    expect(await screen.findByText('Household Member')).toBeInTheDocument();
    expect(screen.getByText('member')).toBeInTheDocument();
    expect(screen.queryByText(/credential/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/password/i)).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /disable member/i })).toHaveLength(1);
    expect(
      screen.queryByRole('button', { name: /disable administrator/i }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /disable member/i }));
    expect(window.confirm).toHaveBeenCalledWith('Disable Household Member?');
    expect(await screen.findByText(/was disabled/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /reactivate member/i }));
    expect(await screen.findByText(/was reactivated/i)).toBeInTheDocument();
    expect(api.reactivateAccount).toHaveBeenCalledWith('member-id');
  });

  it('does not claim a transition succeeded when the server rejects it', async () => {
    api.me.mockResolvedValue({ accountId: 'admin-id', role: 'ADMIN' });
    api.listAccounts.mockResolvedValue([
      {
        accountId: 'member-id',
        loginName: 'member',
        displayName: 'Member',
        role: 'MEMBER',
        status: 'ACTIVE',
        createdAt: '2026-08-15T00:00:00Z',
      },
    ]);
    api.disableAccount.mockRejectedValue(new ApiError(409, 'invalid_transition'));
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /manage household members/i }));
    fireEvent.click(await screen.findByRole('button', { name: /disable member/i }));
    expect(await screen.findByText(/was not changed/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /disable member/i })).toBeInTheDocument();
  });

  it('returns to login when the account-management session expires', async () => {
    api.me.mockResolvedValue({ accountId: 'admin-id', role: 'ADMIN' });
    api.listAccounts.mockRejectedValue(new ApiError(401));
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /manage household members/i }));
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
  });

  it('logs out and returns to login', async () => {
    api.me.mockResolvedValue({ accountId: 'id', role: 'MEMBER' });
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
  });

  it('extracts an invitation fragment and immediately cleans browser history', async () => {
    window.history.replaceState(null, '', '/#invite=one-time-token');
    render(<App />);
    expect(
      await screen.findByRole('heading', { name: /create your household account/i }),
    ).toBeInTheDocument();
    expect(window.location.hash).toBe('');
    expect(window.location.pathname).toBe('/invite');
    expect(screen.queryByLabelText('Invitation token')).not.toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('validates password confirmation and shows compromised feedback', async () => {
    window.history.replaceState(null, '', '/#invite=token');
    api.acceptInvitation.mockRejectedValue(new ApiError(422, 'password_rejected'));
    render(<App />);
    await screen.findByText(/15-128/);
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Member' } });
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'member' } });
    fireEvent.change(screen.getByLabelText('Password'), {
      target: { value: 'a sufficiently long password' },
    });
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'different password value' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/do not match/i);
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'a sufficiently long password' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/not commonly used/i);
  });

  it('creates and displays an invitation without browser storage', async () => {
    api.me.mockResolvedValue({ accountId: 'id', role: 'ADMIN' });
    api.createInvitation.mockResolvedValue({ token: 'token', expiresAt: '2026-08-16T00:00:00Z' });
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /create member invitation/i }));
    fireEvent.click(screen.getByRole('button', { name: /create member invitation/i }));
    expect(await screen.findByDisplayValue(/#invite=token/)).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});

describe('candidate profile workspace', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/');
    api.me.mockResolvedValue({ accountId: 'member-id', role: 'MEMBER' });
    api.logout.mockResolvedValue();
    profile.getProfile.mockResolvedValue(savedProfile);
    profile.listFacts.mockResolvedValue([]);
    profile.createProfile.mockResolvedValue(savedProfile);
    profile.updateProfile.mockResolvedValue({
      ...savedProfile,
      professionalDisplayName: 'Grace Hopper',
      version: 4,
    });
    documents.getBaseResume.mockRejectedValue(new ApiError(404));
    documents.uploadBaseResume.mockResolvedValue(savedResume);
    documents.replaceBaseResume.mockResolvedValue({
      ...savedResume,
      originalFilename: 'updated.docx',
      version: 3,
    });
    documents.downloadBaseResume.mockResolvedValue(new Response(new Blob(['resume'])));
  });

  async function openProfile(role: 'ADMIN' | 'MEMBER' = 'MEMBER') {
    api.me.mockResolvedValue({ accountId: `${role.toLowerCase()}-id`, role });
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /^profile$/i }));
    return screen.findByRole('heading', { name: /candidate profile/i });
  }

  it('makes Profile active for admins and members and restores /profile', async () => {
    window.history.replaceState(null, '', '/profile');
    await openProfile('ADMIN');
    expect(screen.getByRole('button', { name: /^profile$/i })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('button', { name: /create member invitation/i })).toBeInTheDocument();
    expect(profile.getProfile).toHaveBeenCalled();
  });

  it('renders the 404 empty state and creates a profile without owner fields', async () => {
    profile.getProfile.mockRejectedValue(new ApiError(404));
    await openProfile();
    expect(await screen.findByText(/create your profile once/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/professional display name/i), {
      target: { value: 'Ada Candidate' },
    });
    fireEvent.change(screen.getByLabelText(/career summary/i), {
      target: { value: 'Truthful summary' },
    });
    fireEvent.click(screen.getByRole('button', { name: /save profile/i }));
    await waitFor(() => expect(profile.createProfile).toHaveBeenCalled());
    expect(profile.createProfile.mock.calls[0]?.[0]).toEqual(
      expect.not.objectContaining({ ownerAccountId: expect.anything() }),
    );
    expect(await screen.findByText('Ada Candidate')).toBeInTheDocument();
  });

  it('loads, edits with expectedVersion, and cancels without changing saved data', async () => {
    await openProfile();
    expect(await screen.findByText('Ada Candidate')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /edit profile/i }));
    fireEvent.change(screen.getByLabelText(/professional display name/i), {
      target: { value: 'Temporary Name' },
    });
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(screen.getByText('Ada Candidate')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /edit profile/i }));
    fireEvent.change(screen.getByLabelText(/professional display name/i), {
      target: { value: 'Grace Hopper' },
    });
    fireEvent.click(screen.getByRole('button', { name: /save profile/i }));
    await waitFor(() =>
      expect(profile.updateProfile).toHaveBeenCalledWith(
        expect.objectContaining({ professionalDisplayName: 'Grace Hopper', expectedVersion: 3 }),
      ),
    );
  });

  it('prevents invalid profile requests and preserves unsaved conflict data', async () => {
    await openProfile();
    fireEvent.click(screen.getByRole('button', { name: /edit profile/i }));
    fireEvent.change(screen.getByLabelText(/professional display name/i), {
      target: { value: '' },
    });
    fireEvent.click(screen.getByRole('button', { name: /save profile/i }));
    expect(await screen.findByText(/display name is required/i)).toBeInTheDocument();
    expect(profile.updateProfile).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(/professional display name/i), {
      target: { value: 'Unsaved Name' },
    });
    profile.updateProfile.mockRejectedValue(new ApiError(409, 'stale_version'));
    fireEvent.click(screen.getByRole('button', { name: /save profile/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere/i);
    expect(screen.getByDisplayValue('Unsaved Name')).toBeInTheDocument();
    profile.getProfile.mockResolvedValue({
      ...savedProfile,
      professionalDisplayName: 'Server Name',
    });
    fireEvent.click(screen.getByRole('button', { name: /reload latest version/i }));
    expect(await screen.findByText('Server Name')).toBeInTheDocument();
  });

  it('returns to login on profile API 401 without browser storage', async () => {
    profile.getProfile.mockRejectedValue(new ApiError(401));
    await openProfile();
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('uploads a selected base resume explicitly and shows metadata', async () => {
    documents.getBaseResume.mockRejectedValue(new ApiError(404));
    await openProfile();
    expect(await screen.findByText(/no base resume is stored/i)).toBeInTheDocument();
    expect(
      screen.getByText(/does not confirm, import, or change your career facts/i),
    ).toBeInTheDocument();
    const file = new File(['%PDF-1.4\n%%EOF\n'], 'resume.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText(/resume file/i), { target: { files: [file] } });
    expect(documents.uploadBaseResume).not.toHaveBeenCalled();
    expect(screen.getByText(/selected: resume.pdf/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^upload base resume$/i }));
    await waitFor(() => expect(documents.uploadBaseResume).toHaveBeenCalledWith(file));
    expect(await screen.findByText('base-resume.pdf')).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('downloads and replaces the base resume with expected version and conflict reload', async () => {
    documents.getBaseResume.mockResolvedValue(savedResume);
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:resume');
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    await openProfile();
    expect(await screen.findByText('base-resume.pdf')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /download base resume/i }));
    await waitFor(() => expect(documents.downloadBaseResume).toHaveBeenCalled());
    expect(createObjectURL).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:resume');

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    documents.replaceBaseResume.mockRejectedValueOnce(new ApiError(409, 'stale_version'));
    fireEvent.click(screen.getByRole('button', { name: /^replace$/i }));
    const replacement = new File(['docx'], 'updated.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    });
    fireEvent.change(screen.getByLabelText(/resume file/i), { target: { files: [replacement] } });
    fireEvent.click(screen.getByRole('button', { name: /^replace base resume$/i }));
    await waitFor(() => expect(documents.replaceBaseResume).toHaveBeenCalledWith(replacement, 2));
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere/i);
    documents.getBaseResume.mockResolvedValue({
      ...savedResume,
      originalFilename: 'latest.pdf',
      version: 4,
    });
    fireEvent.click(screen.getByRole('button', { name: /reload latest resume/i }));
    expect(await screen.findByText('latest.pdf')).toBeInTheDocument();
  });

  it('rejects invalid base resume selections and returns to login on document 401', async () => {
    await openProfile();
    const textFile = new File(['hello'], 'resume.txt', { type: 'text/plain' });
    fireEvent.change(await screen.findByLabelText(/resume file/i), {
      target: { files: [textFile] },
    });
    expect(await screen.findByRole('alert')).toHaveTextContent(/pdf or docx/i);
    expect(documents.uploadBaseResume).not.toHaveBeenCalled();
  });

  it('returns to login when the document metadata session expires', async () => {
    documents.getBaseResume.mockRejectedValue(new ApiError(401));
    await openProfile();
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
  });
});

describe('career facts workspace', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/profile');
    api.me.mockResolvedValue({ accountId: 'member-id', role: 'MEMBER' });
    api.logout.mockResolvedValue();
    profile.getProfile.mockResolvedValue(savedProfile);
    profile.listFacts.mockResolvedValue([draftFact, confirmedFact, archivedFact]);
    profile.createFact.mockResolvedValue({
      ...draftFact,
      id: 'new-fact',
      factualContent: 'Built an internal tool.',
    });
    profile.updateFact.mockResolvedValue({
      ...confirmedFact,
      status: 'DRAFT',
      factualContent: 'Updated fact',
      version: 6,
    });
    profile.confirmFact.mockResolvedValue({ ...draftFact, status: 'CONFIRMED', version: 3 });
    profile.archiveFact.mockResolvedValue({ ...draftFact, status: 'ARCHIVED', version: 3 });
    profile.restoreFact.mockResolvedValue({ ...archivedFact, status: 'DRAFT', version: 8 });
    documents.getBaseResume.mockResolvedValue(savedResume);
    documents.uploadBaseResume.mockResolvedValue(savedResume);
    documents.replaceBaseResume.mockResolvedValue({
      ...savedResume,
      originalFilename: 'updated.docx',
      version: 3,
    });
    documents.downloadBaseResume.mockResolvedValue(new Response(new Blob(['resume'])));
  });

  async function renderProfile() {
    render(<App />);
    return screen.findByText('Led a migration project.');
  }

  it('renders facts, empty states, and exact enum filters', async () => {
    await renderProfile();
    expect(screen.getAllByText('Employment').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Confirmed').length).toBeGreaterThan(0);
    fireEvent.change(screen.getByLabelText(/^category$/i), { target: { value: 'SKILL' } });
    fireEvent.change(screen.getByLabelText(/^status$/i), { target: { value: 'CONFIRMED' } });
    await waitFor(() =>
      expect(profile.listFacts).toHaveBeenLastCalledWith({
        category: 'SKILL',
        status: 'CONFIRMED',
        limit: 100,
      }),
    );
    profile.listFacts.mockResolvedValue([]);
    fireEvent.change(screen.getByLabelText(/^status$/i), { target: { value: 'ARCHIVED' } });
    expect(await screen.findByText(/no career facts match/i)).toBeInTheDocument();
  });

  it('creates draft facts with validation and no owner or status fields', async () => {
    await renderProfile();
    fireEvent.click(screen.getByRole('button', { name: /^add career fact$/i }));
    fireEvent.click(screen.getByRole('button', { name: /save career fact/i }));
    expect(await screen.findByText(/factual content is required/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/factual content/i), {
      target: { value: 'Built an internal tool.' },
    });
    fireEvent.change(screen.getByLabelText(/start date/i), { target: { value: '2026-02-01' } });
    fireEvent.change(screen.getByLabelText(/end date/i), { target: { value: '2026-01-01' } });
    fireEvent.click(screen.getByRole('button', { name: /save career fact/i }));
    expect(await screen.findByText(/cannot precede/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/end date/i), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: /save career fact/i }));
    await waitFor(() => expect(profile.createFact).toHaveBeenCalled());
    expect(profile.createFact.mock.calls[0]?.[0]).toEqual(
      expect.not.objectContaining({ ownerAccountId: expect.anything(), status: expect.anything() }),
    );
    expect(await screen.findByText('Built an internal tool.')).toBeInTheDocument();
  });

  it('warns before editing confirmed facts and keeps archived facts read-only', async () => {
    await renderProfile();
    const editButtons = screen.getAllByRole('button', { name: /^edit$/i });
    expect(editButtons).toHaveLength(2);
    fireEvent.click(editButtons[1]!);
    expect(screen.getByText(/return it to draft/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/factual content/i), {
      target: { value: 'Updated fact' },
    });
    fireEvent.click(screen.getByRole('button', { name: /save career fact/i }));
    await waitFor(() =>
      expect(profile.updateFact).toHaveBeenCalledWith(
        'fact-confirmed',
        expect.objectContaining({ expectedVersion: 5, factualContent: 'Updated fact' }),
      ),
    );
  });

  it('requires attestation to confirm and sends the versioned truthfulness request', async () => {
    await renderProfile();
    fireEvent.click(screen.getByRole('button', { name: /confirm as accurate/i }));
    const confirmButtons = screen.getAllByRole('button', { name: /confirm as accurate/i });
    const disabledConfirm = confirmButtons.find((button) => button.hasAttribute('disabled'));
    expect(disabledConfirm).toBeDefined();
    fireEvent.click(
      screen.getByRole('checkbox', {
        name: /i confirm that this career fact is accurate/i,
      }),
    );
    fireEvent.click(disabledConfirm!);
    await waitFor(() =>
      expect(profile.confirmFact).toHaveBeenCalledWith('fact-draft', {
        expectedVersion: 2,
        confirmedAccurate: true,
      }),
    );
  });

  it('archives and restores only after explicit confirmation', async () => {
    vi.spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
      .mockReturnValueOnce(true);
    await renderProfile();
    fireEvent.click(screen.getAllByRole('button', { name: /^archive$/i })[0]!);
    expect(profile.archiveFact).not.toHaveBeenCalled();
    fireEvent.click(screen.getAllByRole('button', { name: /^archive$/i })[0]!);
    await waitFor(() =>
      expect(profile.archiveFact).toHaveBeenCalledWith('fact-draft', { expectedVersion: 2 }),
    );
    fireEvent.click(screen.getAllByRole('button', { name: /restore to draft/i })[1]!);
    await waitFor(() =>
      expect(profile.restoreFact).toHaveBeenCalledWith('fact-archived', { expectedVersion: 7 }),
    );
  });

  it('does not optimistically mutate failed writes and handles conflicts and 401', async () => {
    await renderProfile();
    profile.updateFact.mockRejectedValue(new ApiError(409, 'stale_version'));
    fireEvent.click(screen.getAllByRole('button', { name: /^edit$/i })[0]!);
    fireEvent.change(screen.getByLabelText(/factual content/i), {
      target: { value: 'Unsaved fact' },
    });
    fireEvent.click(screen.getByRole('button', { name: /save career fact/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere/i);
    expect(screen.getByDisplayValue('Unsaved fact')).toBeInTheDocument();
    expect(screen.getByText('Led a migration project.')).toBeInTheDocument();

    profile.archiveFact.mockRejectedValue(new ApiError(409, 'stale_version'));
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));
    fireEvent.click(screen.getAllByRole('button', { name: /^archive$/i })[0]!);
    expect(await screen.findByRole('alert')).toHaveTextContent(/refresh before trying/i);

    profile.listFacts.mockRejectedValue(new ApiError(401));
    fireEvent.change(screen.getByLabelText(/^status$/i), { target: { value: 'DRAFT' } });
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
  });

  it('does not render ownerAccountId or write profile data to browser storage', async () => {
    await renderProfile();
    expect(screen.queryByText(/ownerAccountId/i)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('member-id');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});

describe('job and application workspaces', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/');
    api.me.mockResolvedValue({ accountId: 'member-id', role: 'MEMBER' });
    api.logout.mockResolvedValue();
    jobs.listJobs.mockImplementation((filters = {}) =>
      Promise.resolve(filters.archived ? [] : [savedJob]),
    );
    jobs.getJob.mockResolvedValue(savedJob);
    jobs.listSnapshots.mockResolvedValue([savedSnapshot]);
    jobs.captureJob.mockResolvedValue({
      job: { ...savedJob, id: 'created-job', companyName: 'New Co', version: 0 },
      initialSnapshot: { ...savedSnapshot, id: 'created-snapshot', jobId: 'created-job' },
    });
    jobs.updateJob.mockResolvedValue({ ...savedJob, companyName: 'Acme Updated', version: 3 });
    jobs.archiveJob.mockResolvedValue({
      ...savedJob,
      archived: true,
      archivedAt: '2026-08-22T00:00:00Z',
      version: 3,
    });
    jobs.restoreJob.mockResolvedValue({ ...savedJob, version: 4 });
    jobs.appendSnapshot.mockResolvedValue({
      ...savedSnapshot,
      id: 'snapshot-2',
      sequence: 2,
      descriptionText: 'New immutable text.',
    });
    profile.getProfile.mockResolvedValue(savedProfile);
    profile.listFacts.mockResolvedValue([confirmedFact]);
    documents.getBaseResume.mockResolvedValue(savedResume);
    fit.listRequirements.mockResolvedValue([
      draftRequirement,
      confirmedRequirement,
      rejectedRequirement,
    ]);
    fit.listEvidenceLinks.mockImplementation((requirementId) =>
      Promise.resolve(
        requirementId === 'requirement-confirmed'
          ? [
              {
                id: 'link-1',
                jobRequirementId: 'requirement-confirmed',
                evidenceType: 'CAREER_FACT',
                evidenceId: 'fact-confirmed',
                relationship: 'PARTIALLY_SUPPORTS',
                userNote: 'Some relevant work.',
                createdAt: '2026-08-21T00:00:00Z',
                updatedAt: '2026-08-21T00:00:00Z',
                version: 4,
              },
            ]
          : [],
      ),
    );
    fit.getAnalysis.mockResolvedValue(scorableAnalysis);
    fit.createRequirement.mockResolvedValue({
      ...draftRequirement,
      id: 'requirement-created',
      requirementText: 'Own production systems',
      version: 0,
    });
    fit.updateRequirement.mockResolvedValue({ ...confirmedRequirement, version: 6 });
    fit.deleteRequirement.mockResolvedValue(undefined);
    fit.createEvidenceLink.mockResolvedValue({
      id: 'link-created',
      jobRequirementId: 'requirement-confirmed',
      evidenceType: 'CAREER_FACT',
      evidenceId: 'fact-confirmed',
      relationship: 'SUPPORTS',
      userNote: null,
      createdAt: '2026-08-22T00:00:00Z',
      updatedAt: '2026-08-22T00:00:00Z',
      version: 0,
    });
    fit.updateEvidenceLink.mockResolvedValue({
      id: 'link-1',
      jobRequirementId: 'requirement-confirmed',
      evidenceType: 'CAREER_FACT',
      evidenceId: 'fact-confirmed',
      relationship: 'CONTRADICTS',
      userNote: 'Conflict',
      createdAt: '2026-08-21T00:00:00Z',
      updatedAt: '2026-08-22T00:00:00Z',
      version: 5,
    });
    fit.deleteEvidenceLink.mockResolvedValue(undefined);
    applications.listApplications.mockImplementation((filters = {}) =>
      Promise.resolve(filters.archived ? [] : [savedApplication]),
    );
    applications.getApplication.mockResolvedValue(savedApplication);
    applications.listHistory.mockResolvedValue([
      {
        id: 'history-1',
        previousStatus: null,
        newStatus: 'DRAFT',
        effectiveAt: '2026-08-20T00:00:00Z',
        note: null,
        recordedAt: '2026-08-20T00:00:00Z',
      },
      {
        id: 'history-2',
        previousStatus: 'DRAFT',
        newStatus: 'READY_TO_APPLY',
        effectiveAt: '2026-08-21T00:00:00Z',
        note: 'Ready',
        recordedAt: '2026-08-21T00:00:00Z',
      },
    ]);
    applications.createApplication.mockResolvedValue({
      ...savedApplication,
      id: 'created-application',
      status: 'DRAFT',
      version: 0,
    });
    applications.updateApplication.mockResolvedValue({
      ...savedApplication,
      privateNotes: 'Updated note',
      version: 4,
    });
    applications.transitionApplication.mockResolvedValue({
      ...savedApplication,
      status: 'APPLIED',
      appliedAt: '2026-08-22T12:00:00Z',
      nextActionText: null,
      nextActionDueDate: null,
      version: 4,
    });
    applications.archiveApplication.mockResolvedValue({
      ...savedApplication,
      archived: true,
      archivedAt: '2026-08-22T00:00:00Z',
      version: 4,
    });
    applications.restoreApplication.mockResolvedValue({ ...savedApplication, version: 5 });
  });

  async function openJobs() {
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /^jobs$/i }));
    await screen.findByRole('heading', { name: /^jobs$/i });
    return screen.findByText('Build reliable systems.');
  }

  async function openApplications() {
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /^applications$/i }));
    await screen.findByRole('heading', { name: /^applications$/i });
    return screen.findByText(/application created/i);
  }

  it('activates Jobs navigation, restores /jobs, and loads active and archived lists', async () => {
    window.history.replaceState(null, '', '/jobs');
    render(<App />);
    expect(await screen.findByRole('heading', { name: /^jobs$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^jobs$/i })).toHaveAttribute('aria-current', 'page');
    expect(await screen.findByText('Acme')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /archived jobs/i }));
    await waitFor(() =>
      expect(jobs.listJobs).toHaveBeenLastCalledWith({ archived: true, limit: 100 }),
    );
    expect(await screen.findByText(/no archived jobs/i)).toBeInTheDocument();
  });

  it('opens fit review for an exact snapshot and restores the nested route without private URL data', async () => {
    window.history.replaceState(null, '', '/jobs/job-id/snapshots/snapshot-id/fit');
    render(<App />);
    expect(
      await screen.findByRole('heading', { name: /review fit for this snapshot/i }),
    ).toBeInTheDocument();
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('Platform Engineer')).toBeInTheDocument();
    expect(screen.getByText(/snapshot 1 captured/i)).toBeInTheDocument();
    expect(
      screen.getByText(/requirements are interpretations of this exact snapshot/i),
    ).toBeInTheDocument();
    expect(window.location.pathname).toBe('/jobs/job-id/snapshots/snapshot-id/fit');
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('');
    expect(window.location.href).not.toContain('Build%20reliable');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('shows the fit action for snapshots and the no-snapshot empty state', async () => {
    await openJobs();
    expect(screen.getByRole('button', { name: /review fit/i })).toBeInTheDocument();
    cleanup();
    vi.clearAllMocks();
    jobs.listJobs.mockImplementation((filters = {}) =>
      Promise.resolve(filters.archived ? [] : [savedJob]),
    );
    jobs.getJob.mockResolvedValue(savedJob);
    jobs.listSnapshots.mockResolvedValue([]);
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /^jobs$/i }));
    expect(
      await screen.findByText(/add a job-description snapshot before reviewing fit/i),
    ).toBeInTheDocument();
  });

  it('opens metadata editing in a named form with the selected company field', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /^edit metadata$/i }));
    const metadataForm = screen.getByRole('form', { name: /^edit metadata$/i });
    expect(within(metadataForm).getByLabelText(/company name/i)).toHaveValue('Acme');
    fireEvent.click(within(metadataForm).getByRole('button', { name: /^cancel$/i }));
    expect(screen.queryByRole('form', { name: /^edit metadata$/i })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /^acme$/i })).toBeInTheDocument();
  });

  it('opens application note editing in a named form with private notes', async () => {
    await openApplications();
    fireEvent.click(screen.getByRole('button', { name: /edit notes and next action/i }));
    const notesForm = screen.getByRole('form', { name: /notes and next action/i });
    expect(within(notesForm).getByLabelText(/private notes/i)).toHaveValue('Use tailored resume.');
    fireEvent.click(within(notesForm).getByRole('button', { name: /^cancel$/i }));
    expect(screen.queryByRole('form', { name: /notes and next action/i })).not.toBeInTheDocument();
  });

  it('manually creates draft requirements, sends no ownership fields, and refreshes analysis after success', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /review fit/i }));
    expect(await screen.findByRole('heading', { name: /requirements/i })).toBeInTheDocument();
    expect(screen.getByText('Use TypeScript')).toBeInTheDocument();
    expect(screen.getAllByText('Build reliable systems').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Own a flying car')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /add requirement/i }));
    expect(screen.getByLabelText(/review status/i)).toHaveValue('DRAFT');
    fireEvent.change(screen.getByLabelText(/requirement text/i), {
      target: { value: 'Own production systems' },
    });
    fireEvent.change(screen.getAllByLabelText(/^importance$/i)[0]!, {
      target: { value: 'REQUIRED' },
    });
    fireEvent.click(screen.getByRole('button', { name: /save requirement/i }));
    await waitFor(() => expect(fit.createRequirement).toHaveBeenCalled());
    expect(fit.createRequirement.mock.calls[0]?.[2]).toEqual(
      expect.objectContaining({
        requirementText: 'Own production systems',
        status: 'DRAFT',
        importance: 'REQUIRED',
      }),
    );
    expect(fit.createRequirement.mock.calls[0]?.[2]).toEqual(
      expect.not.objectContaining({
        ownerAccountId: expect.anything(),
        accountId: expect.anything(),
      }),
    );
    expect(fit.getAnalysis.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('uses expectedVersion for edits and preserves unsaved requirement values on conflict', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /review fit/i }));
    await screen.findByText('Use TypeScript');
    fireEvent.click(screen.getAllByRole('button', { name: /^edit$/i })[0]!);
    fireEvent.change(screen.getByLabelText(/requirement text/i), {
      target: { value: 'Unsaved requirement text' },
    });
    fit.updateRequirement.mockRejectedValueOnce(new ApiError(409, 'stale_version'));
    fireEvent.click(screen.getByRole('button', { name: /save requirement/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere/i);
    expect(screen.getByDisplayValue('Unsaved requirement text')).toBeInTheDocument();
    expect(fit.updateRequirement).toHaveBeenCalledWith(
      'requirement-draft',
      expect.objectContaining({ expectedVersion: 2 }),
    );
    fireEvent.click(screen.getByRole('button', { name: /reload latest/i }));
    await waitFor(() => expect(fit.listRequirements.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('confirms, rejects, returns to draft, and deletes only after deliberate confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /review fit/i }));
    await screen.findByText('Use TypeScript');
    fireEvent.click(screen.getAllByRole('button', { name: /^confirm$/i })[0]!);
    await waitFor(() =>
      expect(fit.updateRequirement).toHaveBeenCalledWith(
        'requirement-draft',
        expect.objectContaining({ status: 'CONFIRMED', expectedVersion: 2 }),
      ),
    );
    await screen.findByText(/requirement confirmed/i);
    fireEvent.click(screen.getAllByRole('button', { name: /^reject$/i })[0]!);
    await screen.findByText(/requirement rejected/i);
    fireEvent.click(screen.getAllByRole('button', { name: /return to draft/i })[0]!);
    await screen.findByText(/requirement returned to draft/i);
    fireEvent.click(screen.getAllByRole('button', { name: /^delete$/i })[0]!);
    await waitFor(() => expect(fit.deleteRequirement).toHaveBeenCalled());
    expect(window.confirm).toHaveBeenCalled();
  });

  it('links eligible evidence with no preselected relationship and preserves duplicate-link input', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /review fit/i }));
    expect((await screen.findAllByText('Build reliable systems')).length).toBeGreaterThanOrEqual(1);
    fireEvent.click(screen.getByRole('button', { name: /link evidence/i }));
    expect(screen.getByText(/not demonstrated means/i)).toBeInTheDocument();
    const relationshipRadios = screen.getAllByRole('radio');
    relationshipRadios.forEach((radio) => expect(radio).not.toBeChecked());
    fireEvent.change(screen.getByLabelText(/evidence source/i), {
      target: { value: 'CAREER_FACT' },
    });
    expect(
      screen.getAllByText(/skill: uses typescript professionally/i).length,
    ).toBeGreaterThanOrEqual(1);
    fireEvent.change(screen.getByLabelText(/^evidence$/i), { target: { value: 'fact-confirmed' } });
    fireEvent.click(relationshipRadios[0]!);
    fireEvent.change(screen.getByLabelText(/user note/i), { target: { value: 'Selected note' } });
    fit.createEvidenceLink.mockRejectedValueOnce(new ApiError(409, 'duplicate_evidence_link'));
    fireEvent.click(screen.getByRole('button', { name: /save evidence link/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/conflicts/i);
    expect(screen.getByDisplayValue('Selected note')).toBeInTheDocument();
    expect(fit.createEvidenceLink).toHaveBeenCalledWith(
      'requirement-confirmed',
      expect.not.objectContaining({
        ownerAccountId: expect.anything(),
        accountId: expect.anything(),
      }),
    );
  });

  it('edits and removes evidence links with expectedVersion and confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /review fit/i }));
    expect((await screen.findAllByText(/some relevant work/i)).length).toBeGreaterThanOrEqual(1);
    fireEvent.click(screen.getByRole('button', { name: /edit link/i }));
    fireEvent.click(screen.getAllByRole('radio')[2]!);
    fireEvent.change(screen.getByLabelText(/user note/i), { target: { value: 'Conflict' } });
    fireEvent.click(screen.getByRole('button', { name: /save evidence link/i }));
    await waitFor(() =>
      expect(fit.updateEvidenceLink).toHaveBeenCalledWith(
        'link-1',
        expect.objectContaining({ relationship: 'CONTRADICTS', expectedVersion: 4 }),
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: /remove link/i }));
    await waitFor(() => expect(fit.deleteEvidenceLink).toHaveBeenCalledWith('link-1', 4));
  });

  it('renders support and coverage separately, non-scorable state, findings, filters, and safe copy', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /review fit/i }));
    expect(await screen.findByText(/evidence support: 50%/i)).toBeInTheDocument();
    expect(screen.getByText(/review coverage: 100%/i)).toBeInTheDocument();
    expect(
      screen.getByText(/how much of the confirmed requirement weight is demonstrated/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        /how much of the confirmed requirement weight has been explicitly reviewed/i,
      ),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/no confirmed requirements/i).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/partially demonstrated/i).length).toBeGreaterThanOrEqual(1);
    expect(
      screen.getAllByText(/current evidence partially demonstrates/i).length,
    ).toBeGreaterThanOrEqual(1);
    expect(
      screen.getAllByText(/both supporting and contradicting evidence are linked/i).length,
    ).toBeGreaterThanOrEqual(1);
    expect(screen.queryByText(/hiring probability/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/fit score/i)).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^assessment$/i), { target: { value: 'DEMONSTRATED' } });
    expect(
      await screen.findByText(/no confirmed requirement explanations match/i),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /clear filters/i }));
    expect((await screen.findAllByText('Build reliable systems')).length).toBeGreaterThanOrEqual(1);

    fit.getAnalysis.mockResolvedValueOnce(nonScorableAnalysis);
    fireEvent.click(screen.getByRole('button', { name: /refresh analysis/i }));
    expect(
      await screen.findByText(/confirm at least one reviewed requirement/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/evidence support: 0%/i)).not.toBeInTheDocument();
  });

  it('keeps last analysis visible on failed refresh and explains oversized analysis safely', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /review fit/i }));
    expect(await screen.findByText(/evidence support: 50%/i)).toBeInTheDocument();
    fit.getAnalysis.mockRejectedValueOnce(new ApiError(409, 'analysis_too_large'));
    fireEvent.click(screen.getByRole('button', { name: /refresh analysis/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/too many reviewed inputs/i);
    expect(screen.getByText(/evidence support: 50%/i)).toBeInTheDocument();
  });

  it('filters loaded jobs by case-insensitive search, source, employment, and clears filters', async () => {
    jobs.listJobs.mockImplementation((filters = {}) =>
      Promise.resolve(filters.archived ? [archivedJob] : [savedJob, differentJob]),
    );
    await openJobs();
    expect(await screen.findByText(/showing 2 of 2 loaded active jobs/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/search loaded jobs/i), {
      target: { value: '  acME  ' },
    });
    expect(await screen.findByText(/showing 1 of 2 loaded active jobs/i)).toBeInTheDocument();
    let activeJobsList = screen.getByRole('list', { name: /active jobs/i });
    expect(activeJobsList).toHaveTextContent('Acme');
    expect(activeJobsList).not.toHaveTextContent('Initech');
    fireEvent.change(screen.getByLabelText(/^source$/i), {
      target: { value: 'PASTED_DESCRIPTION' },
    });
    expect(await screen.findByText(/no loaded jobs match these filters/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/search loaded jobs/i), { target: { value: '' } });
    expect(await screen.findByText('Initech')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^employment$/i), {
      target: { value: 'FULL_TIME' },
    });
    expect(await screen.findByText(/no loaded jobs match these filters/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /clear filters/i }));
    expect(await screen.findByText(/showing 2 of 2 loaded active jobs/i)).toBeInTheDocument();
    activeJobsList = screen.getByRole('list', { name: /active jobs/i });
    expect(activeJobsList).toHaveTextContent('Acme');
    expect(activeJobsList).toHaveTextContent('Initech');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('warns for exact posting URL duplicates, lets the owner review, and does not persist warning data', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /capture job/i }));
    fireEvent.change(screen.getAllByLabelText(/company name/i)[0]!, {
      target: { value: 'Another Acme' },
    });
    fireEvent.change(screen.getAllByLabelText(/job title/i)[0]!, {
      target: { value: 'Similar Role' },
    });
    fireEvent.change(screen.getAllByLabelText(/source type/i)[0]!, {
      target: { value: 'URL_REFERENCE' },
    });
    fireEvent.change(screen.getAllByLabelText(/posting url/i)[0]!, {
      target: { value: 'https://example.test/job/' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: /^capture job$/i }).at(-1)!);
    expect(await screen.findByRole('alert')).toHaveTextContent(/exact posting url match/i);
    expect(jobs.captureJob).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /review existing job/i }));
    expect(await screen.findByRole('heading', { name: 'Acme' })).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('warns for external posting ID with matching context and still allows intentional capture', async () => {
    jobs.listJobs.mockImplementation((filters = {}) =>
      Promise.resolve(filters.archived ? [] : [{ ...savedJob, externalPostingId: 'ACME-42' }]),
    );
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /capture job/i }));
    fireEvent.change(screen.getAllByLabelText(/company name/i)[0]!, {
      target: { value: ' acme ' },
    });
    fireEvent.change(screen.getAllByLabelText(/job title/i)[0]!, {
      target: { value: 'Platform Engineer II' },
    });
    fireEvent.change(screen.getByLabelText(/external posting id/i), {
      target: { value: ' acme-42 ' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: /^capture job$/i }).at(-1)!);
    expect(await screen.findByRole('alert')).toHaveTextContent(/external posting id/i);
    fireEvent.click(screen.getByRole('button', { name: /continue capturing/i }));
    await waitFor(() => expect(jobs.captureJob).toHaveBeenCalled());
  });

  it('warns for possible company-title duplicates but not clearly different jobs', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /capture job/i }));
    fireEvent.change(screen.getAllByLabelText(/company name/i)[0]!, {
      target: { value: 'ACME' },
    });
    fireEvent.change(screen.getAllByLabelText(/job title/i)[0]!, {
      target: { value: ' platform   engineer ' },
    });
    fireEvent.change(screen.getAllByLabelText(/work location/i)[0]!, {
      target: { value: 'Remote' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: /^capture job$/i }).at(-1)!);
    expect(await screen.findByRole('alert')).toHaveTextContent(/same company and job title/i);
    fireEvent.click(screen.getByRole('button', { name: /continue capturing/i }));
    await waitFor(() => expect(jobs.captureJob).toHaveBeenCalledTimes(1));

    jobs.captureJob.mockClear();
    fireEvent.click(screen.getByRole('button', { name: /capture job/i }));
    fireEvent.change(screen.getAllByLabelText(/company name/i)[0]!, {
      target: { value: 'Different Co' },
    });
    fireEvent.change(screen.getAllByLabelText(/job title/i)[0]!, {
      target: { value: 'Different Role' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: /^capture job$/i }).at(-1)!);
    await waitFor(() => expect(jobs.captureJob).toHaveBeenCalledTimes(1));
    expect(screen.queryByText(/this looks similar/i)).not.toBeInTheDocument();
  });

  it('captures jobs with validation, renders initial snapshot, and never fetches posting URLs', async () => {
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /capture job/i }));
    fireEvent.change(screen.getAllByLabelText(/source type/i)[0]!, {
      target: { value: 'PASTED_DESCRIPTION' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: /^capture job$/i }).at(-1)!);
    expect(await screen.findByText(/company name is required/i)).toBeInTheDocument();
    fireEvent.change(screen.getAllByLabelText(/company name/i)[0]!, {
      target: { value: 'New Co' },
    });
    fireEvent.change(screen.getAllByLabelText(/job title/i)[0]!, { target: { value: 'Builder' } });
    fireEvent.click(screen.getAllByRole('button', { name: /^capture job$/i }).at(-1)!);
    expect((await screen.findAllByText(/pasted description requires/i))[0]).toBeInTheDocument();
    fireEvent.change(screen.getAllByLabelText(/description text/i)[0]!, {
      target: { value: 'Do good work.' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: /^capture job$/i }).at(-1)!);
    await waitFor(() =>
      expect(jobs.captureJob).toHaveBeenCalledWith(
        expect.objectContaining({ companyName: 'New Co', sourceType: 'PASTED_DESCRIPTION' }),
      ),
    );
    expect(await screen.findByText(/initial snapshot/i)).toBeInTheDocument();
    expect(jobs.captureJob.mock.calls[0]?.[0]).toEqual(
      expect.not.objectContaining({
        ownerAccountId: expect.anything(),
        accountId: expect.anything(),
      }),
    );
  });

  it('edits jobs with expectedVersion, preserves unsaved conflicts, and handles snapshots', async () => {
    await openJobs();
    expect(await screen.findByText('Build reliable systems.')).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: /edit metadata/i }));
    fireEvent.change(screen.getAllByLabelText(/company name/i).at(-1)!, {
      target: { value: 'Unsaved Co' },
    });
    jobs.updateJob.mockRejectedValueOnce(new ApiError(409, 'stale_version'));
    fireEvent.click(screen.getAllByRole('button', { name: /edit metadata/i }).at(-1)!);
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere/i);
    expect(screen.getByDisplayValue('Unsaved Co')).toBeInTheDocument();
    jobs.updateJob.mockResolvedValueOnce({ ...savedJob, companyName: 'Saved Co', version: 3 });
    fireEvent.click(screen.getByRole('button', { name: /reload latest job/i }));
    await waitFor(() => expect(jobs.getJob.mock.calls.length).toBeGreaterThanOrEqual(2));
    fireEvent.change(screen.getAllByLabelText(/description text/i).at(-1)!, {
      target: { value: 'New immutable text.' },
    });
    fireEvent.click(screen.getAllByRole('button', { name: /append snapshot/i }).at(-1)!);
    await waitFor(() => expect(jobs.appendSnapshot).toHaveBeenCalled());
    expect(await screen.findByText(/immutable snapshot appended/i)).toBeInTheDocument();
  });

  it('archives and restores jobs only after confirmation and hides edit controls for archived jobs', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await openJobs();
    fireEvent.click(screen.getByRole('button', { name: /^archive$/i }));
    await waitFor(() => expect(jobs.archiveJob).toHaveBeenCalledWith('job-id', 2));
    jobs.listJobs.mockImplementation((filters = {}) =>
      Promise.resolve(
        filters.archived
          ? [{ ...savedJob, archived: true, archivedAt: '2026-08-22T00:00:00Z' }]
          : [],
      ),
    );
    fireEvent.click(screen.getByRole('button', { name: /archived jobs/i }));
    expect(await screen.findByText('Acme')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /edit metadata/i })).not.toBeInTheDocument();
  });

  it('activates Applications navigation and supports status-filtered lists', async () => {
    window.history.replaceState(null, '', '/applications');
    render(<App />);
    expect(await screen.findByRole('heading', { name: /^applications$/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^applications$/i })).toHaveAttribute(
      'aria-current',
      'page',
    );
    fireEvent.change(screen.getByLabelText(/^status$/i), { target: { value: 'READY_TO_APPLY' } });
    await waitFor(() =>
      expect(applications.listApplications).toHaveBeenLastCalledWith({
        archived: false,
        status: 'READY_TO_APPLY',
        limit: 100,
      }),
    );
  });

  it('filters loaded applications by status, text, due state, and clears filters without storage', async () => {
    const overdueApplication = {
      ...savedApplication,
      id: 'overdue-application',
      jobId: 'different-job-id',
      status: 'DRAFT' as const,
      nextActionText: 'Send follow-up',
      nextActionDueDate: '2026-01-01',
    };
    jobs.listJobs.mockImplementation((filters = {}) =>
      Promise.resolve(filters.archived ? [] : [savedJob, differentJob]),
    );
    applications.listApplications.mockImplementation((filters = {}) =>
      Promise.resolve(filters.archived ? [] : [savedApplication, overdueApplication]),
    );
    window.history.replaceState(null, '', '/applications');
    render(<App />);
    expect(await screen.findByRole('heading', { name: /^applications$/i })).toBeInTheDocument();
    expect(
      await screen.findByText(/showing 2 of 2 loaded active applications/i),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/search loaded applications/i), {
      target: { value: 'initech' },
    });
    expect(
      await screen.findByText(/showing 1 of 2 loaded active applications/i),
    ).toBeInTheDocument();
    const activeApplicationsList = screen.getByRole('list', { name: /active applications/i });
    expect(activeApplicationsList).toHaveTextContent('Initech');
    expect(activeApplicationsList).not.toHaveTextContent('Acme');
    fireEvent.change(screen.getByLabelText(/due state/i), { target: { value: 'upcoming' } });
    expect(
      await screen.findByText(/no loaded applications match these filters/i),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/^status$/i), { target: { value: 'DRAFT' } });
    await waitFor(() =>
      expect(applications.listApplications).toHaveBeenLastCalledWith({
        archived: false,
        status: 'DRAFT',
        limit: 100,
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: /clear filters/i }));
    expect(
      await screen.findByText(/showing 2 of 2 loaded active applications/i),
    ).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('creates a draft application without owner or initial status fields and handles duplicates safely', async () => {
    applications.listApplications.mockResolvedValue([]);
    applications.createApplication.mockRejectedValueOnce(
      new ApiError(409, 'duplicate_application'),
    );
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /^applications$/i }));
    await screen.findByRole('heading', { name: /^applications$/i });
    fireEvent.click(screen.getByRole('button', { name: /create application/i }));
    expect(screen.getByText(/starts it as draft/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/captured job/i), { target: { value: 'job-id' } });
    fireEvent.click(screen.getByRole('button', { name: /create draft application/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/already exists/i);
    applications.createApplication.mockResolvedValueOnce({
      ...savedApplication,
      id: 'created-application',
      status: 'DRAFT',
      version: 0,
    });
    fireEvent.click(screen.getByRole('button', { name: /create draft application/i }));
    await waitFor(() =>
      expect(applications.createApplication).toHaveBeenCalledWith(
        expect.objectContaining({ jobId: 'job-id' }),
      ),
    );
    expect(applications.createApplication.mock.calls[0]?.[0]).toEqual(
      expect.not.objectContaining({
        ownerAccountId: expect.anything(),
        status: expect.anything(),
        previousStatus: expect.anything(),
      }),
    );
  });

  it('updates notes with expectedVersion and preserves unsaved values on conflict', async () => {
    await openApplications();
    fireEvent.click(await screen.findByRole('button', { name: /edit notes and next action/i }));
    fireEvent.change(screen.getAllByLabelText(/private notes/i).at(-1)!, {
      target: { value: 'Unsaved note' },
    });
    applications.updateApplication.mockRejectedValueOnce(new ApiError(409, 'stale_version'));
    fireEvent.click(screen.getByRole('button', { name: /save application notes/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere/i);
    expect(screen.getByDisplayValue('Unsaved note')).toBeInTheDocument();
    expect(applications.updateApplication).toHaveBeenCalledWith(
      'application-id',
      expect.objectContaining({ expectedVersion: 3 }),
    );
    applications.updateApplication.mockResolvedValueOnce({ ...savedApplication, version: 4 });
    fireEvent.change(document.querySelector<HTMLInputElement>('#application-next-action')!, {
      target: { value: '' },
    });
    fireEvent.change(
      document.querySelector<HTMLInputElement>('#application-next-action-due-date')!,
      { target: { value: '2026-09-01' } },
    );
    fireEvent.click(screen.getByRole('button', { name: /save application notes/i }));
    expect(await screen.findByText(/due date requires/i)).toBeInTheDocument();
  });

  it('records status only after confirmation, sends appliedAt only for applied, and renders history oldest-first', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await openApplications();
    expect(await screen.findByText(/application created/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/next status/i), { target: { value: 'APPLIED' } });
    fireEvent.change(screen.getByLabelText(/applied date and time/i), {
      target: { value: '2026-08-22T12:00' },
    });
    fireEvent.click(screen.getByRole('button', { name: /record status/i }));
    await waitFor(() =>
      expect(applications.transitionApplication).toHaveBeenCalledWith(
        'application-id',
        expect.objectContaining({
          targetStatus: 'APPLIED',
          expectedVersion: 3,
          appliedAt: expect.any(String),
        }),
      ),
    );
    expect(await screen.findByText(/status recorded/i)).toBeInTheDocument();
  });

  it('requires note for another interview stage and warns terminal transitions clear next actions', async () => {
    applications.getApplication.mockResolvedValue({ ...savedApplication, status: 'INTERVIEWING' });
    await openApplications();
    fireEvent.change(await screen.findByLabelText(/next status/i), {
      target: { value: 'INTERVIEWING' },
    });
    fireEvent.click(screen.getByRole('button', { name: /record status/i }));
    expect(await screen.findByText(/meaningful note/i)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/next status/i), { target: { value: 'WITHDRAWN' } });
    expect(screen.getByText(/active next actions will be cleared/i)).toBeInTheDocument();
  });

  it('archives applications with preserved status messaging and returns to login on 401 without storage', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    applications.archiveApplication.mockRejectedValue(new ApiError(401));
    await openApplications();
    fireEvent.click(await screen.findByRole('button', { name: /^archive$/i }));
    await waitFor(() =>
      expect(applications.archiveApplication).toHaveBeenCalledWith('application-id', 3),
    );
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});
