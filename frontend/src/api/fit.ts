import { apiDelete, apiGet, apiPost, apiPut } from './client';

export type RequirementCategory =
  | 'SKILL'
  | 'EXPERIENCE'
  | 'EDUCATION'
  | 'CERTIFICATION'
  | 'DOMAIN_KNOWLEDGE'
  | 'RESPONSIBILITY'
  | 'LOCATION'
  | 'WORK_AUTHORIZATION'
  | 'OTHER';
export type RequirementImportance = 'REQUIRED' | 'PREFERRED' | 'UNSPECIFIED';
export type RequirementStatus = 'DRAFT' | 'CONFIRMED' | 'REJECTED';
export type EvidenceType = 'CAREER_FACT' | 'PROFILE_FIELD' | 'RESUME_VERSION';
export type EvidenceRelationship =
  'SUPPORTS' | 'PARTIALLY_SUPPORTS' | 'CONTRADICTS' | 'NOT_DEMONSTRATED';
export type RequirementAssessment =
  | 'DEMONSTRATED'
  | 'PARTIALLY_DEMONSTRATED'
  | 'NOT_DEMONSTRATED'
  | 'CONTRADICTED'
  | 'CONFLICTING_EVIDENCE'
  | 'UNASSESSED';
export type FitReasonCode =
  | 'SUPPORTING_EVIDENCE'
  | 'PARTIAL_EVIDENCE'
  | 'NO_DEMONSTRATING_EVIDENCE'
  | 'CONTRADICTING_EVIDENCE'
  | 'SUPPORTING_AND_CONTRADICTING_EVIDENCE'
  | 'NO_LINKED_EVIDENCE';
export type FitFindingType =
  | 'UNASSESSED_REQUIREMENT'
  | 'EVIDENCE_NOT_DEMONSTRATED'
  | 'PARTIAL_EVIDENCE'
  | 'CONTRADICTING_EVIDENCE'
  | 'CONFLICTING_EVIDENCE';

export interface JobRequirement {
  id: string;
  jobId: string;
  jobSnapshotId: string;
  category: RequirementCategory;
  importance: RequirementImportance;
  requirementText: string;
  sourceExcerpt?: string | null;
  status: RequirementStatus;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface RequirementFields {
  category: RequirementCategory;
  importance: RequirementImportance;
  requirementText: string;
  sourceExcerpt?: string | null;
  status: RequirementStatus;
}

export interface RequirementUpdateFields extends RequirementFields {
  expectedVersion: number;
}

export interface CandidateEvidenceLink {
  id: string;
  jobRequirementId: string;
  evidenceType: EvidenceType;
  evidenceId: string;
  relationship: EvidenceRelationship;
  userNote?: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface EvidenceLinkFields {
  evidenceType: EvidenceType;
  evidenceId: string;
  relationship: EvidenceRelationship;
  userNote?: string | null;
}

export interface EvidenceLinkUpdateFields extends EvidenceLinkFields {
  expectedVersion: number;
}

export interface EvidenceRelationshipCounts {
  supportsCount: number;
  partiallySupportsCount: number;
  contradictsCount: number;
  notDemonstratedCount: number;
}

export interface EvidenceLinkSummary {
  evidenceLinkId: string;
  evidenceType: EvidenceType;
  evidenceId: string;
  relationship: EvidenceRelationship;
  userNote?: string | null;
  evidenceReference: string;
}

export interface RequirementAssessmentResponse {
  requirementId: string;
  requirementCategory: RequirementCategory;
  importance: RequirementImportance;
  requirementStatus: RequirementStatus;
  requirementText: string;
  sourceExcerpt?: string | null;
  assessment: RequirementAssessment;
  reasonCode: FitReasonCode;
  requirementWeight: number;
  evidenceCredit: number;
  weightedContribution: number;
  evidenceRelationshipCounts: EvidenceRelationshipCounts;
  evidenceLinks: EvidenceLinkSummary[];
}

export interface ImportanceBreakdown {
  importance: RequirementImportance;
  applicable: boolean;
  confirmedRequirementCount: number;
  totalWeight: number;
  assessedCount: number;
  demonstratedCount: number;
  partiallyDemonstratedCount: number;
  notDemonstratedCount: number;
  contradictedCount: number;
  conflictingEvidenceCount: number;
  unassessedCount: number;
  evidenceSupportScore?: number | null;
  evidenceCoverageScore?: number | null;
}

export interface FitFinding {
  findingType: FitFindingType;
  requirementId: string;
  requirementCategory: RequirementCategory;
  importance: RequirementImportance;
  assessment: RequirementAssessment;
  reasonCode: FitReasonCode;
}

export interface FitAnalysis {
  policyVersion: string;
  analysisStatus: 'SCORABLE' | 'NO_CONFIRMED_REQUIREMENTS';
  jobId: string;
  snapshotId: string;
  evidenceSupportScore?: number | null;
  evidenceCoverageScore?: number | null;
  confirmedRequirementCount: number;
  draftRequirementCount: number;
  rejectedRequirementCount: number;
  totalEligibleWeight: number;
  supportPoints: number;
  assessedWeight: number;
  importanceBreakdowns: ImportanceBreakdown[];
  requirementAssessments: RequirementAssessmentResponse[];
  gaps: FitFinding[];
  contradictions: FitFinding[];
}

export const requirementCategories: RequirementCategory[] = [
  'SKILL',
  'EXPERIENCE',
  'EDUCATION',
  'CERTIFICATION',
  'DOMAIN_KNOWLEDGE',
  'RESPONSIBILITY',
  'LOCATION',
  'WORK_AUTHORIZATION',
  'OTHER',
];
export const requirementImportances: RequirementImportance[] = [
  'REQUIRED',
  'PREFERRED',
  'UNSPECIFIED',
];
export const requirementStatuses: RequirementStatus[] = ['DRAFT', 'CONFIRMED', 'REJECTED'];
export const evidenceRelationships: EvidenceRelationship[] = [
  'SUPPORTS',
  'PARTIALLY_SUPPORTS',
  'CONTRADICTS',
  'NOT_DEMONSTRATED',
];

export const profileEvidenceFields = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    field: 'professionalDisplayName',
    label: 'Professional display name',
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    field: 'professionalHeadline',
    label: 'Professional headline',
  },
  { id: '00000000-0000-0000-0000-000000000003', field: 'careerSummary', label: 'Career summary' },
  {
    id: '00000000-0000-0000-0000-000000000004',
    field: 'locationPreference',
    label: 'Location preference',
  },
  { id: '00000000-0000-0000-0000-000000000005', field: 'targetRoles', label: 'Target roles' },
  {
    id: '00000000-0000-0000-0000-000000000006',
    field: 'workAuthorizationStatement',
    label: 'Work authorization statement',
  },
  {
    id: '00000000-0000-0000-0000-000000000007',
    field: 'workLocationPreferences',
    label: 'Work-location preferences',
  },
] as const;

export const fitApi = {
  listRequirements: (jobId: string, snapshotId: string, limit = 100) =>
    apiGet<JobRequirement[]>(
      `/api/jobs/${encodeURIComponent(jobId)}/snapshots/${encodeURIComponent(
        snapshotId,
      )}/requirements?limit=${Math.min(Math.max(limit, 1), 100)}`,
    ),
  createRequirement: (jobId: string, snapshotId: string, request: RequirementFields) =>
    apiPost<JobRequirement>(
      `/api/jobs/${encodeURIComponent(jobId)}/snapshots/${encodeURIComponent(
        snapshotId,
      )}/requirements`,
      request,
    ),
  updateRequirement: (requirementId: string, request: RequirementUpdateFields) =>
    apiPut<JobRequirement>(`/api/job-requirements/${encodeURIComponent(requirementId)}`, request),
  deleteRequirement: (requirementId: string, expectedVersion: number) =>
    apiDelete<void>(`/api/job-requirements/${encodeURIComponent(requirementId)}`, {
      expectedVersion,
    }),
  listEvidenceLinks: (requirementId: string, limit = 100) =>
    apiGet<CandidateEvidenceLink[]>(
      `/api/job-requirements/${encodeURIComponent(requirementId)}/evidence-links?limit=${Math.min(
        Math.max(limit, 1),
        100,
      )}`,
    ),
  createEvidenceLink: (requirementId: string, request: EvidenceLinkFields) =>
    apiPost<CandidateEvidenceLink>(
      `/api/job-requirements/${encodeURIComponent(requirementId)}/evidence-links`,
      request,
    ),
  updateEvidenceLink: (linkId: string, request: EvidenceLinkUpdateFields) =>
    apiPut<CandidateEvidenceLink>(
      `/api/job-requirement-evidence/${encodeURIComponent(linkId)}`,
      request,
    ),
  deleteEvidenceLink: (linkId: string, expectedVersion: number) =>
    apiDelete<void>(`/api/job-requirement-evidence/${encodeURIComponent(linkId)}`, {
      expectedVersion,
    }),
  getAnalysis: (jobId: string, snapshotId: string) =>
    apiGet<FitAnalysis>(
      `/api/jobs/${encodeURIComponent(jobId)}/snapshots/${encodeURIComponent(
        snapshotId,
      )}/fit-analysis`,
    ),
};
