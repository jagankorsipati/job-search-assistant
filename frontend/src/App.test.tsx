import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ApiError, authApi } from './api/auth';

vi.mock('./api/auth', async (original) => {
  const actual = await original<typeof import('./api/auth')>();
  return {
    ...actual,
    authApi: {
      me: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
      createInvitation: vi.fn(),
      acceptInvitation: vi.fn(),
      listAccounts: vi.fn(),
      disableAccount: vi.fn(),
      reactivateAccount: vi.fn(),
    },
  };
});
const api = vi.mocked(authApi);

describe('authentication experience', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
    window.history.replaceState(null, '', '/');
    api.me.mockRejectedValue(new ApiError(401));
    api.logout.mockResolvedValue();
  });

  it('restores an authenticated admin session and exposes invitation controls', async () => {
    api.me.mockResolvedValue({ accountId: 'id', role: 'ADMIN' });
    render(<App />);
    expect(await screen.findByText('Signed in as admin')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create member invitation/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /manage household members/i })).toBeInTheDocument();
  });

  it('handles generic login failure, rate limiting, and success', async () => {
    api.login
      .mockRejectedValueOnce(new ApiError(401))
      .mockRejectedValueOnce(new ApiError(429, undefined, '30'))
      .mockResolvedValue({ accountId: 'id', role: 'MEMBER' });
    render(<App />);
    await screen.findByRole('heading', { name: /sign in/i });
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'member' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/incorrect/i);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/30 seconds/i);
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Signed in as member')).toBeInTheDocument();
    expect(screen.queryByText(/create member invitation/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/manage household members/i)).not.toBeInTheDocument();
  });

  it('renders only approved account fields and confirms a server-backed disable', async () => {
    api.me.mockResolvedValue({ accountId: 'admin-id', role: 'ADMIN' });
    api.listAccounts.mockResolvedValue([
      {
        accountId: 'admin-id',
        loginName: 'admin',
        displayName: 'Administrator',
        role: 'ADMIN',
        status: 'ACTIVE',
        createdAt: '2026-08-15T00:00:00Z',
      },
      {
        accountId: 'member-id',
        loginName: 'member',
        displayName: 'Household Member',
        role: 'MEMBER',
        status: 'ACTIVE',
        createdAt: '2026-08-15T00:00:00Z',
      },
    ]);
    api.disableAccount.mockResolvedValue();
    api.reactivateAccount.mockResolvedValue();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /manage household members/i }));
    expect(await screen.findByText('Household Member')).toBeInTheDocument();
    expect(screen.getByText('member')).toBeInTheDocument();
    expect(screen.queryByText(/credential/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/password/i)).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /disable member/i })).toHaveLength(1);
    expect(
      screen.queryByRole('button', { name: /disable administrator/i }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /disable member/i }));
    expect(window.confirm).toHaveBeenCalledWith('Disable Household Member?');
    expect(await screen.findByText(/was disabled/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /reactivate member/i }));
    expect(await screen.findByText(/was reactivated/i)).toBeInTheDocument();
    expect(api.reactivateAccount).toHaveBeenCalledWith('member-id');
  });

  it('does not claim a transition succeeded when the server rejects it', async () => {
    api.me.mockResolvedValue({ accountId: 'admin-id', role: 'ADMIN' });
    api.listAccounts.mockResolvedValue([
      {
        accountId: 'member-id',
        loginName: 'member',
        displayName: 'Member',
        role: 'MEMBER',
        status: 'ACTIVE',
        createdAt: '2026-08-15T00:00:00Z',
      },
    ]);
    api.disableAccount.mockRejectedValue(new ApiError(409, 'invalid_transition'));
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /manage household members/i }));
    fireEvent.click(await screen.findByRole('button', { name: /disable member/i }));
    expect(await screen.findByText(/was not changed/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /disable member/i })).toBeInTheDocument();
  });

  it('returns to login when the account-management session expires', async () => {
    api.me.mockResolvedValue({ accountId: 'admin-id', role: 'ADMIN' });
    api.listAccounts.mockRejectedValue(new ApiError(401));
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /manage household members/i }));
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
  });

  it('logs out and returns to login', async () => {
    api.me.mockResolvedValue({ accountId: 'id', role: 'MEMBER' });
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
  });

  it('extracts an invitation fragment and immediately cleans browser history', async () => {
    window.history.replaceState(null, '', '/#invite=one-time-token');
    render(<App />);
    expect(
      await screen.findByRole('heading', { name: /create your household account/i }),
    ).toBeInTheDocument();
    expect(window.location.hash).toBe('');
    expect(window.location.pathname).toBe('/invite');
    expect(screen.queryByLabelText('Invitation token')).not.toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('validates password confirmation and shows compromised feedback', async () => {
    window.history.replaceState(null, '', '/#invite=token');
    api.acceptInvitation.mockRejectedValue(new ApiError(422, 'password_rejected'));
    render(<App />);
    await screen.findByText(/15–128/);
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Member' } });
    fireEvent.change(screen.getByLabelText('Login name'), { target: { value: 'member' } });
    fireEvent.change(screen.getByLabelText('Password'), {
      target: { value: 'a sufficiently long password' },
    });
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'different password value' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/do not match/i);
    fireEvent.change(screen.getByLabelText('Confirm password'), {
      target: { value: 'a sufficiently long password' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/not commonly used/i);
  });

  it('creates and displays an invitation without browser storage', async () => {
    api.me.mockResolvedValue({ accountId: 'id', role: 'ADMIN' });
    api.createInvitation.mockResolvedValue({ token: 'token', expiresAt: '2026-08-16T00:00:00Z' });
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /create member invitation/i }));
    fireEvent.click(screen.getByRole('button', { name: /create member invitation/i }));
    expect(await screen.findByDisplayValue(/#invite=token/)).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});
