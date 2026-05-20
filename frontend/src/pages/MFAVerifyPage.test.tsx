import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MFAVerifyPage } from './MFAVerifyPage';
import { AuthProvider } from '@/features/auth/context/AuthContext';
import { apiClient } from '@/lib/apiClient';

vi.mock('@/lib/apiClient', () => ({
  apiClient: { post: vi.fn() },
  configureAuthInterceptor: vi.fn(),
}));

function renderAt(entry: { pathname: string; state?: unknown }) {
  const qc = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[entry]}>
        <AuthProvider>
          <Routes>
            <Route path="/mfa-verify" element={<MFAVerifyPage />} />
            <Route path="/login" element={<div data-testid="login-route">login</div>} />
            <Route path="/dashboard" element={<div data-testid="dashboard-route">dash</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const validState = {
  tempSessionId: 'temp-abc-123',
  email: 'juan@example.com',
  expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('<MFAVerifyPage />', () => {
  it('sin state en location redirige a /login', () => {
    renderAt({ pathname: '/mfa-verify' });
    expect(screen.getByTestId('login-route')).toBeInTheDocument();
  });

  it('renderiza 6 inputs y el botón Verificar arranca deshabilitado', () => {
    renderAt({ pathname: '/mfa-verify', state: validState });

    const slots = screen.getAllByRole('textbox');
    expect(slots).toHaveLength(6);
    expect(screen.getByRole('button', { name: /verificar/i })).toBeDisabled();
  });

  it('pegar 6 dígitos auto-llena todos los slots y habilita Verificar', async () => {
    const user = userEvent.setup();
    renderAt({ pathname: '/mfa-verify', state: validState });

    const firstSlot = screen.getAllByRole('textbox')[0];
    firstSlot.focus();
    await user.paste('123456');

    const slots = screen.getAllByRole('textbox') as HTMLInputElement[];
    expect(slots.map((s) => s.value).join('')).toBe('123456');
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /verificar/i })).toBeEnabled(),
    );
  });

  it('on success guarda sesión y navega a /dashboard', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      data: {
        accessToken: 'jwt.signed.token',
        expiresIn: 900,
        user: {
          id: '550e8400-e29b-41d4-a716-446655440000',
          email: 'juan@example.com',
          nombreCompleto: 'Juan Pérez García',
          rol: 'INVESTOR',
        },
      },
    });
    const user = userEvent.setup();
    renderAt({ pathname: '/mfa-verify', state: validState });

    const firstSlot = screen.getAllByRole('textbox')[0];
    firstSlot.focus();
    await user.paste('123456');
    await user.click(screen.getByRole('button', { name: /verificar/i }));

    await waitFor(() => {
      expect(screen.getByTestId('dashboard-route')).toBeInTheDocument();
    });
    expect(apiClient.post).toHaveBeenCalledWith('/auth/mfa/verify', {
      tempSessionId: 'temp-abc-123',
      code: '123456',
    });
  });
});
