import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { App } from './App';
import { checkBackendHealth } from './api/health';

vi.mock('./api/health', () => ({
  checkBackendHealth: vi.fn(),
}));

const mockedCheckBackendHealth = vi.mocked(checkBackendHealth);

describe('App', () => {
  beforeEach(() => {
    mockedCheckBackendHealth.mockReset();
    mockedCheckBackendHealth.mockResolvedValue({ connected: true });
  });

  it('renders the application shell and integrity promise', () => {
    render(<App />);

    expect(screen.getByRole('heading', { name: /a calmer place/i })).toBeInTheDocument();
    expect(
      screen.getByText(/must never invent skills, achievements, or metrics/i),
    ).toBeInTheDocument();
  });

  it('renders every planned navigation label as upcoming', () => {
    render(<App />);

    for (const label of ['Dashboard', 'Profile', 'Jobs', 'Documents', 'Applications']) {
      const item = screen.getByText(label).closest('[aria-disabled="true"]');
      expect(item).toHaveTextContent('Upcoming');
    }
  });

  it('shows the connected backend state', async () => {
    render(<App />);

    expect(await screen.findByText('Backend connected')).toBeInTheDocument();
  });

  it('shows the unavailable backend state without internal details', async () => {
    mockedCheckBackendHealth.mockRejectedValue(new Error('connection refused at localhost:8080'));

    render(<App />);

    expect(await screen.findByText('Backend unavailable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText(/localhost|database|connection refused/i)).not.toBeInTheDocument();
  });
});
