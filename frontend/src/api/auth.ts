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
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code?: string,
    public readonly retryAfter?: string,
  ) {
    super('Request failed');
  }
}
interface CsrfToken {
  token: string;
  headerName: string;
  parameterName: string;
}
let csrf: CsrfToken | undefined;
async function csrfToken(): Promise<CsrfToken> {
  if (csrf !== undefined) return csrf;
  const response = await fetch('/api/auth/csrf', { headers: { Accept: 'application/json' } });
  if (!response.ok) throw await apiError(response);
  csrf = (await response.json()) as CsrfToken;
  return csrf;
}
async function request<T>(url: string, body?: unknown): Promise<T> {
  const token = await csrfToken();
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      [token.headerName]: token.token,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (response.status === 401) csrf = undefined;
  if (!response.ok) throw await apiError(response);
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}
async function apiError(response: Response): Promise<ApiError> {
  let code: string | undefined;
  try {
    code = ((await response.json()) as { code?: string }).code;
  } catch {
    /* generic response */
  }
  return new ApiError(response.status, code, response.headers.get('Retry-After') ?? undefined);
}
export const authApi = {
  async me(): Promise<Identity> {
    const response = await fetch('/api/auth/me', { headers: { Accept: 'application/json' } });
    if (!response.ok) throw await apiError(response);
    return (await response.json()) as Identity;
  },
  login: (loginName: string, password: string) =>
    request<Identity>('/api/auth/login', { loginName, password }),
  async logout(): Promise<void> {
    await request<void>('/api/auth/logout');
    csrf = undefined;
  },
  createInvitation: () => request<Invitation>('/api/admin/invitations'),
  acceptInvitation: (acceptance: Acceptance) =>
    request<Identity>('/api/invitations/accept', acceptance),
};
