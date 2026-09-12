import { apiDelete, apiGet, apiPost, apiPut } from './client';

export const targetSections = [
  'SUMMARY',
  'EXPERIENCE',
  'SKILLS',
  'PROJECTS',
  'EDUCATION',
  'CERTIFICATIONS',
  'OTHER',
] as const;
export interface ProposalFields {
  targetSection: (typeof targetSections)[number];
  targetReference: string;
  originalText: string | null;
  proposedText: string;
  evidence: { careerFactId: string; userNote: string | null }[];
}
export interface SourceResume {
  documentId: string;
  version: number;
  sha256Checksum: string;
}
export interface Proposal extends ProposalFields {
  id: string;
  sourceResume: SourceResume;
  version: number;
  lifecycleStatus: 'DRAFT' | 'APPROVED' | 'REJECTED';
  evidenceState: 'MISSING_EVIDENCE' | 'SUPPORTED_BY_CONFIRMED_FACTS';
  originalTextSource: 'USER_SUPPLIED';
  originalTextVerification: 'NOT_CHECKED_AGAINST_DOCUMENT';
  createdAt: string;
  updatedAt: string;
}
export interface ProposalReview {
  proposal: Proposal;
  eligibility: { eligible: boolean; reasons: string[] };
  reviewRevision: string;
  evidenceReferences: { careerFactId: string; version: number }[];
  originalTextNotice: string;
  evidenceNotice: string;
  approvalNotice: string;
}
export interface ProposalDecision {
  id: string;
  proposalId: string;
  decisionType: 'APPROVED' | 'REJECTED';
  proposalVersion: number;
  sourceResume: SourceResume;
  attestedExperienceAccurate?: boolean;
  decidedAt: string;
  evidenceReferences: { careerFactId: string; version: number }[];
}
const base = '/api/documents/resume-tailoring-proposals';
const path = (id: string) => `${base}/${encodeURIComponent(id)}`;
// Explicit payload projection keeps UI-only state and attribution out of writes.
const fields = (input: ProposalFields): ProposalFields => ({
  targetSection: input.targetSection,
  targetReference: input.targetReference,
  originalText: input.originalText?.trim() || null,
  proposedText: input.proposedText,
  evidence: input.evidence.map(({ careerFactId, userNote }) => ({ careerFactId, userNote })),
});
export const tailoringApi = {
  list: () => apiGet<Proposal[]>(`${base}?limit=100`),
  get: (id: string) => apiGet<Proposal>(path(id)),
  create: (input: ProposalFields, source: SourceResume) =>
    apiPost<Proposal>(base, {
      ...fields(input),
      sourceResumeDocumentId: source.documentId,
      sourceResumeVersion: source.version,
      sourceResumeSha256Checksum: source.sha256Checksum,
    }),
  update: (id: string, input: ProposalFields, expectedVersion: number) =>
    apiPut<Proposal>(path(id), { ...fields(input), expectedVersion }),
  remove: (id: string, expectedVersion: number) => apiDelete<void>(path(id), { expectedVersion }),
  review: (id: string) => apiGet<ProposalReview>(`${path(id)}/review`),
  approve: (id: string, expectedVersion: number, reviewedRevision: string) =>
    apiPost<ProposalDecision>(`${path(id)}/approve`, {
      expectedVersion,
      reviewedRevision,
      attestedExperienceAccurate: true,
    }),
  reject: (id: string, expectedVersion: number) =>
    apiPost<ProposalDecision>(`${path(id)}/reject`, { expectedVersion }),
};
