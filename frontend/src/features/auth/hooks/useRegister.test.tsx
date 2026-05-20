import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useRegister } from './useRegister';
import { apiClient } from '@/lib/apiClient';
import type { RegisterRequest, RegisterResponse } from '@/types/api';

vi.mock('@/lib/apiClient', () => ({
  apiClient: { post: vi.fn() },
}));

const payload: RegisterRequest = {
  email: 'juan@example.com',
  password: 'SecurePass123',
  nombreCompleto: 'Juan Pérez García',
  tipoDocumento: 'CC',
  numeroDocumento: '1234567890',
  telefono: '+573001234567',
  aceptaTerminos: true,
};

const ok: RegisterResponse = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  email: 'juan@example.com',
  nombreCompleto: 'Juan Pérez García',
  rol: 'INVESTOR',
  estado: 'ACTIVE',
  createdAt: '2026-05-19T14:00:00Z',
};

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useRegister', () => {
  it('llama POST /auth/register con el payload y devuelve RegisterResponse en 201', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({ data: ok });

    const { result } = renderHook(() => useRegister(), { wrapper });
    result.current.mutate(payload);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(apiClient.post).toHaveBeenCalledWith('/auth/register', payload);
    expect(result.current.data).toEqual(ok);
  });

  it('normaliza un 409 a ParsedError con code EMAIL_ALREADY_REGISTERED', async () => {
    vi.mocked(apiClient.post).mockRejectedValueOnce({
      isAxiosError: true,
      response: {
        data: {
          timestamp: '2026-05-19T00:00:00Z',
          status: 409,
          error: 'EMAIL_ALREADY_REGISTERED',
          message: 'duplicate',
          path: '/api/v1/auth/register',
          traceId: 'abc',
        },
        status: 409,
      },
    });

    const { result } = renderHook(() => useRegister(), { wrapper });
    result.current.mutate(payload);

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.code).toBe('EMAIL_ALREADY_REGISTERED');
    expect(result.current.error?.status).toBe(409);
  });
});
