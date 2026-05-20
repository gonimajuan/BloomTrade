import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { ReactNode } from 'react';
import { AuthProvider, useAuth } from './AuthContext';
import type { UserSummary } from '@/types/api';

const USER: UserSummary = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  email: 'juan@example.com',
  nombreCompleto: 'Juan Pérez García',
  rol: 'INVESTOR',
};

function wrapper({ children }: { children: ReactNode }) {
  return (
    <MemoryRouter>
      <AuthProvider>{children}</AuthProvider>
    </MemoryRouter>
  );
}

describe('AuthContext', () => {
  it('arranca con sesión vacía', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.accessToken).toBeNull();
    expect(result.current.user).toBeNull();
  });

  it('setSession llena user, accessToken e isAuthenticated', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.setSession('jwt.signed.token', USER));

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.accessToken).toBe('jwt.signed.token');
    expect(result.current.user).toEqual(USER);
  });

  it('clearSession vuelve a estado vacío tras una sesión activa', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    act(() => result.current.setSession('jwt.token', USER));

    act(() => result.current.clearSession());

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.accessToken).toBeNull();
    expect(result.current.user).toBeNull();
  });

  it('useAuth fuera de <AuthProvider> tira con mensaje explícito', () => {
    // Mutea el error de React Test Renderer sobre el throw — el assert es lo importante.
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useAuth())).toThrow(/AuthProvider/);
    spy.mockRestore();
  });
});
