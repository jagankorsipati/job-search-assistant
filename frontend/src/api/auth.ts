import { apiGet, apiPost, ApiError, resetCsrf } from './client';

export type Role = 'ADMIN' | 'MEMBER';
export interface Identity {
  accountId: string;
  role: Role;
}
export interface Invitation {
  token: string;
  expiresAt: string;
}
export interface Acceptance {
  token: string;
  loginName: string;
  displayName: string;
  password: string;
}
export type AccountStatus = 'PENDING_ACTIVATION' | 'ACTIVE' | 'DISABLED';
export interface ManagedAccount {
  accountId: string;
  loginName: string;
  displayName: string;
  role: Role;
  status: AccountStatus;
  createdAt: string;
}
export { ApiError };
export const authApi = {
  me: () => apiGet<Identity>('/api/auth/me'),
  login: (loginName: string, password: string) =>
    apiPost<Identity>('/api/auth/login', { loginName, password }),
  async logout(): Promise<void> {
    await apiPost<void>('/api/auth/logout');
    resetCsrf();
  },
  createInvitation: () => apiPost<Invitation>('/api/admin/invitations'),
  listAccounts: () => apiGet<ManagedAccount[]>('/api/admin/accounts'),
  disableAccount: (accountId: string) =>
    apiPost<void>(`/api/admin/accounts/${encodeURIComponent(accountId)}/disable`),
  reactivateAccount: (accountId: string) =>
    apiPost<void>(`/api/admin/accounts/${encodeURIComponent(accountId)}/reactivate`),
  acceptInvitation: (acceptance: Acceptance) =>
    apiPost<Identity>('/api/invitations/accept', acceptance),
};
