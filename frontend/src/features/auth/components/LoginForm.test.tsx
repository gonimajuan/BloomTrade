import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { LoginForm } from './LoginForm';
import { apiClient } from '@/lib/apiClient';

vi.mock('@/lib/apiClient', () => ({
  apiClient: { post: vi.fn() },
  configureAuthInterceptor: vi.fn(),
}));

function MfaVerifyProbe() {
  const location = useLocation();
  const state = location.state as { tempSessionId?: string; email?: string } | null;
  return (
    <div data-testid="mfa-probe">
      <span data-testid="temp">{state?.tempSessionId ?? ''}</span>
      <span data-testid="email">{state?.email ?? ''}</span>
    </div>
  );
}

function renderForm() {
  const qc = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/login']}>
        <Routes>
          <Route path="/login" element={<LoginForm />} />
          <Route path="/mfa-verify" element={<MfaVerifyProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('<LoginForm />', () => {
  it('inicia con el botón "Iniciar sesión" deshabilitado', () => {
    renderForm();
    expect(screen.getByRole('button', { name: /iniciar sesión/i })).toBeDisabled();
  });

  it('al completar credenciales válidas dispara /auth/login y navega a /mfa-verify con state', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      data: { tempSessionId: 'temp-abc-123', expiresInSeconds: 300 },
    });
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/email/i), 'juan@example.com');
    await user.type(screen.getByLabelText(/password/i), 'SecurePass123');

    const submit = screen.getByRole('button', { name: /iniciar sesión/i });
    await waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith('/auth/login', {
        email: 'juan@example.com',
        password: 'SecurePass123',
      });
    });

    // Tras el success debe estar montada la página de MFA con el state propagado.
    const probe = await screen.findByTestId('mfa-probe');
    expect(probe).toBeInTheDocument();
    expect(screen.getByTestId('temp')).toHaveTextContent('temp-abc-123');
    expect(screen.getByTestId('email')).toHaveTextContent('juan@example.com');
  });

  it('muestra banner "Credenciales inválidas" cuando el backend devuelve 401', async () => {
    vi.mocked(apiClient.post).mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        status: 401,
        data: {
          timestamp: '2026-05-20T00:00:00Z',
          status: 401,
          error: 'INVALID_CREDENTIALS',
          message: 'Credenciales inválidas',
          path: '/api/v1/auth/login',
          traceId: 't',
        },
      },
    });
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/email/i), 'juan@example.com');
    await user.type(screen.getByLabelText(/password/i), 'WrongPass999');
    await user.click(screen.getByRole('button', { name: /iniciar sesión/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/credenciales inválidas/i);
  });
});
