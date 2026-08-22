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

export function resetCsrf() {
  csrf = undefined;
}

async function csrfToken(): Promise<CsrfToken> {
  if (csrf !== undefined) return csrf;
  const response = await fetch('/api/auth/csrf', { headers: { Accept: 'application/json' } });
  if (!response.ok) throw await apiError(response);
  csrf = (await response.json()) as CsrfToken;
  return csrf;
}

export async function apiGet<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    headers: { Accept: 'application/json', 'Cache-Control': 'no-store' },
    cache: 'no-store',
  });
  if (response.status === 401) resetCsrf();
  if (!response.ok) throw await apiError(response);
  return (await response.json()) as T;
}

export async function apiPost<T>(url: string, body?: unknown): Promise<T> {
  return apiWrite<T>('POST', url, body);
}

export async function apiPut<T>(url: string, body?: unknown): Promise<T> {
  return apiWrite<T>('PUT', url, body);
}

async function apiWrite<T>(method: 'POST' | 'PUT', url: string, body?: unknown): Promise<T> {
  const token = await csrfToken();
  const response = await fetch(url, {
    method,
    headers: {
      Accept: 'application/json',
      'Cache-Control': 'no-store',
      'Content-Type': 'application/json',
      [token.headerName]: token.token,
    },
    cache: 'no-store',
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (response.status === 401) resetCsrf();
  if (!response.ok) throw await apiError(response);
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

async function apiError(response: Response): Promise<ApiError> {
  let code: string | undefined;
  try {
    code = ((await response.json()) as { code?: string }).code;
  } catch {
    /* keep generic failure */
  }
  return new ApiError(response.status, code, response.headers.get('Retry-After') ?? undefined);
}
