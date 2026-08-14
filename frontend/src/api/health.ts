const HEALTH_ENDPOINT = '/actuator/health';

interface HealthResponse {
  status?: unknown;
}

export interface BackendHealth {
  connected: true;
}

export async function checkBackendHealth(signal?: AbortSignal): Promise<BackendHealth> {
  const response = await fetch(HEALTH_ENDPOINT, {
    headers: { Accept: 'application/json' },
    ...(signal === undefined ? {} : { signal }),
  });

  if (!response.ok) {
    throw new Error('Backend health request failed.');
  }

  const body: unknown = await response.json();

  if (!isHealthyResponse(body)) {
    throw new Error('Backend health response was not healthy.');
  }

  return { connected: true };
}

function isHealthyResponse(value: unknown): value is HealthResponse & { status: 'UP' } {
  return typeof value === 'object' && value !== null && 'status' in value && value.status === 'UP';
}
