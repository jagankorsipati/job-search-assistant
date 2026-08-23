import { apiGet, apiPost, apiPut } from './client';

export type ApplicationStatus =
  | 'DRAFT'
  | 'READY_TO_APPLY'
  | 'APPLIED'
  | 'INTERVIEWING'
  | 'OFFER'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'WITHDRAWN';

export interface JobApplication {
  id: string;
  jobId: string;
  status: ApplicationStatus;
  appliedAt?: string | null;
  nextActionText?: string | null;
  nextActionDueDate?: string | null;
  privateNotes?: string | null;
  statusChangedAt: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  archivedAt?: string | null;
  archived: boolean;
}

export interface ApplicationStatusHistory {
  id: string;
  previousStatus?: ApplicationStatus | null;
  newStatus: ApplicationStatus;
  effectiveAt: string;
  note?: string | null;
  recordedAt: string;
}

export interface CreateApplicationRequest {
  jobId: string;
  privateNotes?: string | null;
  nextActionText?: string | null;
  nextActionDueDate?: string | null;
}

export interface UpdateApplicationRequest {
  privateNotes?: string | null;
  nextActionText?: string | null;
  nextActionDueDate?: string | null;
  expectedVersion: number;
}

export interface TransitionApplicationRequest {
  targetStatus: ApplicationStatus;
  expectedVersion: number;
  appliedAt?: string | null;
  note?: string | null;
}

export const applicationStatuses: ApplicationStatus[] = [
  'DRAFT',
  'READY_TO_APPLY',
  'APPLIED',
  'INTERVIEWING',
  'OFFER',
  'ACCEPTED',
  'REJECTED',
  'WITHDRAWN',
];

export const allowedTransitions: Record<ApplicationStatus, ApplicationStatus[]> = {
  DRAFT: ['READY_TO_APPLY', 'WITHDRAWN'],
  READY_TO_APPLY: ['DRAFT', 'APPLIED', 'WITHDRAWN'],
  APPLIED: ['INTERVIEWING', 'OFFER', 'REJECTED', 'WITHDRAWN'],
  INTERVIEWING: ['INTERVIEWING', 'OFFER', 'REJECTED', 'WITHDRAWN'],
  OFFER: ['ACCEPTED', 'REJECTED', 'WITHDRAWN'],
  ACCEPTED: [],
  REJECTED: [],
  WITHDRAWN: [],
};

export const applicationsApi = {
  listApplications: (
    filters: { archived?: boolean; status?: ApplicationStatus | ''; limit?: number } = {},
  ) => {
    const params = new URLSearchParams();
    params.set('archived', String(filters.archived ?? false));
    if (filters.status) params.set('status', filters.status);
    params.set('limit', String(Math.min(Math.max(filters.limit ?? 100, 1), 100)));
    return apiGet<JobApplication[]>(`/api/applications?${params.toString()}`);
  },
  createApplication: (request: CreateApplicationRequest) =>
    apiPost<JobApplication>('/api/applications', request),
  getApplication: (applicationId: string) =>
    apiGet<JobApplication>(`/api/applications/${encodeURIComponent(applicationId)}`),
  updateApplication: (applicationId: string, request: UpdateApplicationRequest) =>
    apiPut<JobApplication>(`/api/applications/${encodeURIComponent(applicationId)}`, request),
  transitionApplication: (applicationId: string, request: TransitionApplicationRequest) =>
    apiPost<JobApplication>(
      `/api/applications/${encodeURIComponent(applicationId)}/transitions`,
      request,
    ),
  listHistory: (applicationId: string, limit = 100) =>
    apiGet<ApplicationStatusHistory[]>(
      `/api/applications/${encodeURIComponent(applicationId)}/history?limit=${Math.min(Math.max(limit, 1), 100)}`,
    ),
  archiveApplication: (applicationId: string, expectedVersion: number) =>
    apiPost<JobApplication>(`/api/applications/${encodeURIComponent(applicationId)}/archive`, {
      expectedVersion,
    }),
  restoreApplication: (applicationId: string, expectedVersion: number) =>
    apiPost<JobApplication>(`/api/applications/${encodeURIComponent(applicationId)}/restore`, {
      expectedVersion,
    }),
};
