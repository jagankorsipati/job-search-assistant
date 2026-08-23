import { apiGet, apiPost, apiPut } from './client';

export type JobSourceType = 'MANUAL' | 'PASTED_DESCRIPTION' | 'URL_REFERENCE';
export type EmploymentType =
  'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'TEMPORARY' | 'INTERNSHIP' | 'OTHER';

export interface CapturedJob {
  id: string;
  companyName: string;
  jobTitle: string;
  workLocation?: string | null;
  postingUrl?: string | null;
  sourceType: JobSourceType;
  employmentType?: EmploymentType | null;
  externalPostingId?: string | null;
  datePosted?: string | null;
  capturedAt: string;
  metadataUpdatedAt: string;
  version: number;
  archivedAt?: string | null;
  archived: boolean;
}

export interface JobDescriptionSnapshot {
  id: string;
  jobId: string;
  sequence: number;
  sourceType: JobSourceType;
  descriptionText: string;
  sha256Digest: string;
  capturedAt: string;
}

export interface CaptureJobRequest {
  companyName: string;
  jobTitle: string;
  workLocation?: string | null;
  postingUrl?: string | null;
  sourceType: JobSourceType;
  employmentType?: EmploymentType | null;
  externalPostingId?: string | null;
  datePosted?: string | null;
  descriptionText?: string | null;
}

export interface UpdateJobRequest extends CaptureJobRequest {
  expectedVersion: number;
}

export interface CaptureJobResponse {
  job: CapturedJob;
  initialSnapshot?: JobDescriptionSnapshot | null;
}

export interface AppendSnapshotRequest {
  sourceType: JobSourceType;
  descriptionText: string;
}

export const jobSourceTypes: JobSourceType[] = ['MANUAL', 'PASTED_DESCRIPTION', 'URL_REFERENCE'];
export const employmentTypes: EmploymentType[] = [
  'FULL_TIME',
  'PART_TIME',
  'CONTRACT',
  'TEMPORARY',
  'INTERNSHIP',
  'OTHER',
];

export const jobsApi = {
  listJobs: (filters: { archived?: boolean; limit?: number } = {}) => {
    const params = new URLSearchParams();
    params.set('archived', String(filters.archived ?? false));
    params.set('limit', String(Math.min(Math.max(filters.limit ?? 100, 1), 100)));
    return apiGet<CapturedJob[]>(`/api/jobs?${params.toString()}`);
  },
  captureJob: (request: CaptureJobRequest) => apiPost<CaptureJobResponse>('/api/jobs', request),
  getJob: (jobId: string) => apiGet<CapturedJob>(`/api/jobs/${encodeURIComponent(jobId)}`),
  updateJob: (jobId: string, request: UpdateJobRequest) =>
    apiPut<CapturedJob>(`/api/jobs/${encodeURIComponent(jobId)}`, request),
  archiveJob: (jobId: string, expectedVersion: number) =>
    apiPost<CapturedJob>(`/api/jobs/${encodeURIComponent(jobId)}/archive`, { expectedVersion }),
  restoreJob: (jobId: string, expectedVersion: number) =>
    apiPost<CapturedJob>(`/api/jobs/${encodeURIComponent(jobId)}/restore`, { expectedVersion }),
  listSnapshots: (jobId: string, limit = 50) =>
    apiGet<JobDescriptionSnapshot[]>(
      `/api/jobs/${encodeURIComponent(jobId)}/snapshots?limit=${Math.min(Math.max(limit, 1), 50)}`,
    ),
  getSnapshot: (jobId: string, snapshotId: string) =>
    apiGet<JobDescriptionSnapshot>(
      `/api/jobs/${encodeURIComponent(jobId)}/snapshots/${encodeURIComponent(snapshotId)}`,
    ),
  appendSnapshot: (jobId: string, request: AppendSnapshotRequest) =>
    apiPost<JobDescriptionSnapshot>(`/api/jobs/${encodeURIComponent(jobId)}/snapshots`, request),
};
