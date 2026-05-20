import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { RegisterForm } from './RegisterForm';
import { apiClient } from '@/lib/apiClient';

vi.mock('@/lib/apiClient', () => ({
  apiClient: { post: vi.fn() },
}));

function renderForm() {
  const qc = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/register']}>
        <RegisterForm />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/email/i), 'juan@example.com');
  await user.type(screen.getByLabelText(/password/i), 'SecurePass123');
  await user.type(screen.getByLabelText(/nombre completo/i), 'Juan Pérez García');
  await user.type(screen.getByLabelText(/número documento/i), '1234567890');
  // Teléfono default '+57' → completar resto.
  await user.type(screen.getByLabelText(/teléfono/i), '3001234567');
  await user.click(screen.getByRole('checkbox'));
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('<RegisterForm />', () => {
  it('inicia con el botón "Crear mi cuenta" deshabilitado', () => {
    renderForm();
    expect(
      screen.getByRole('button', { name: /crear mi cuenta/i }),
    ).toBeDisabled();
  });

  it('al completar todos los campos válidos habilita el submit y llama al backend', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({
      data: {
        id: 'uuid',
        email: 'juan@example.com',
        nombreCompleto: 'Juan Pérez García',
        rol: 'INVESTOR',
        estado: 'ACTIVE',
        createdAt: '2026-05-19T14:00:00Z',
      },
    });
    const user = userEvent.setup();
    renderForm();

    await fillValid(user);

    const submit = screen.getByRole('button', { name: /crear mi cuenta/i });
    await waitFor(() => expect(submit).toBeEnabled());
    await user.click(submit);

    await waitFor(() => {
      expect(apiClient.post).toHaveBeenCalledWith(
        '/auth/register',
        expect.objectContaining({
          email: 'juan@example.com',
          tipoDocumento: 'CC',
          aceptaTerminos: true,
        }),
      );
    });
  });

  it('muestra el banner de error cuando el backend devuelve 409', async () => {
    vi.mocked(apiClient.post).mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        data: {
          timestamp: '2026-05-19T00:00:00Z',
          status: 409,
          error: 'EMAIL_ALREADY_REGISTERED',
          message: 'ya registrado',
          path: '/api/v1/auth/register',
          traceId: 't',
        },
        status: 409,
      },
    });
    const user = userEvent.setup();
    renderForm();

    await fillValid(user);
    await user.click(screen.getByRole('button', { name: /crear mi cuenta/i }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/correo ya está registrado/i);
  });
});
