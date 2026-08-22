import { apiGet, apiPost, apiPut } from './client';

export type CareerFactCategory =
  'EMPLOYMENT' | 'SKILL' | 'EDUCATION' | 'CERTIFICATION' | 'PROJECT' | 'ACCOMPLISHMENT';

export type CareerFactStatus = 'DRAFT' | 'CONFIRMED' | 'ARCHIVED';

export interface CandidateProfile {
  id: string;
  professionalDisplayName: string;
  professionalHeadline?: string | null;
  careerSummary?: string | null;
  locationPreference?: string | null;
  targetRoles?: string | null;
  workAuthorizationStatement?: string | null;
  workLocationPreferences?: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface CareerFact {
  id: string;
  category: CareerFactCategory;
  status: CareerFactStatus;
  factualContent: string;
  organization?: string | null;
  title?: string | null;
  location?: string | null;
  startedOn?: string | null;
  endedOn?: string | null;
  ongoing: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export type ProfileFields = Pick<
  CandidateProfile,
  | 'professionalDisplayName'
  | 'professionalHeadline'
  | 'careerSummary'
  | 'locationPreference'
  | 'targetRoles'
  | 'workAuthorizationStatement'
  | 'workLocationPreferences'
>;

export type CareerFactFields = Pick<
  CareerFact,
  | 'category'
  | 'factualContent'
  | 'organization'
  | 'title'
  | 'location'
  | 'startedOn'
  | 'endedOn'
  | 'ongoing'
>;

export type CreateProfileRequest = ProfileFields;
export type UpdateProfileRequest = ProfileFields & { expectedVersion: number };
export type CreateCareerFactRequest = CareerFactFields;
export type UpdateCareerFactRequest = CareerFactFields & { expectedVersion: number };
export interface VersionedLifecycleRequest {
  expectedVersion: number;
}
export interface ConfirmCareerFactRequest extends VersionedLifecycleRequest {
  confirmedAccurate: true;
}

export const careerFactCategories: CareerFactCategory[] = [
  'EMPLOYMENT',
  'SKILL',
  'EDUCATION',
  'CERTIFICATION',
  'PROJECT',
  'ACCOMPLISHMENT',
];

export const careerFactStatuses: CareerFactStatus[] = ['DRAFT', 'CONFIRMED', 'ARCHIVED'];

export const profileApi = {
  getProfile: () => apiGet<CandidateProfile>('/api/profile'),
  createProfile: (request: CreateProfileRequest) =>
    apiPost<CandidateProfile>('/api/profile', request),
  updateProfile: (request: UpdateProfileRequest) =>
    apiPut<CandidateProfile>('/api/profile', request),
  listFacts: (filters: {
    category?: CareerFactCategory | undefined;
    status?: CareerFactStatus | undefined;
    limit?: number | undefined;
  }) => {
    const params = new URLSearchParams();
    if (filters.category) params.set('category', filters.category);
    if (filters.status) params.set('status', filters.status);
    params.set('limit', String(Math.min(Math.max(filters.limit ?? 100, 1), 100)));
    return apiGet<CareerFact[]>(`/api/profile/career-facts?${params.toString()}`);
  },
  createFact: (request: CreateCareerFactRequest) =>
    apiPost<CareerFact>('/api/profile/career-facts', request),
  getFact: (factId: string) =>
    apiGet<CareerFact>(`/api/profile/career-facts/${encodeURIComponent(factId)}`),
  updateFact: (factId: string, request: UpdateCareerFactRequest) =>
    apiPut<CareerFact>(`/api/profile/career-facts/${encodeURIComponent(factId)}`, request),
  confirmFact: (factId: string, request: ConfirmCareerFactRequest) =>
    apiPost<CareerFact>(`/api/profile/career-facts/${encodeURIComponent(factId)}/confirm`, request),
  archiveFact: (factId: string, request: VersionedLifecycleRequest) =>
    apiPost<CareerFact>(`/api/profile/career-facts/${encodeURIComponent(factId)}/archive`, request),
  restoreFact: (factId: string, request: VersionedLifecycleRequest) =>
    apiPost<CareerFact>(`/api/profile/career-facts/${encodeURIComponent(factId)}/restore`, request),
};
