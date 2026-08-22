import { apiDownload, apiGet, apiPostForm, apiPutForm } from './client';

export interface BaseResumeMetadata {
  id: string;
  originalFilename: string;
  mediaType: string;
  byteSize: number;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export const maxBaseResumeBytes = 5 * 1024 * 1024;

function uploadBody(file: File, expectedVersion?: number) {
  const body = new FormData();
  body.append('file', file);
  if (expectedVersion !== undefined) body.append('expectedVersion', String(expectedVersion));
  return body;
}

export const documentsApi = {
  getBaseResume: () => apiGet<BaseResumeMetadata>('/api/documents/base-resume'),
  uploadBaseResume: (file: File) =>
    apiPostForm<BaseResumeMetadata>('/api/documents/base-resume', uploadBody(file)),
  replaceBaseResume: (file: File, expectedVersion: number) =>
    apiPutForm<BaseResumeMetadata>('/api/documents/base-resume', uploadBody(file, expectedVersion)),
  downloadBaseResume: () => apiDownload('/api/documents/base-resume/download'),
};
