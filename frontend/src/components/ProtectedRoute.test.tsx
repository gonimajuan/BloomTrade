import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from './ProtectedRoute';
import { AuthProvider, useAuth } from '@/features/auth/context/AuthContext';
import type { UserSummary } from '@/types/api';

const USER: UserSummary = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  email: 'juan@example.com',
  nombreCompleto: 'Juan Pérez García',
  rol: 'INVESTOR',
};

/**
 * Abre sesión en el AuthContext durante el primer render — equivalente a un post-MFA exitoso.
 * Patrón "setState durante render protegido por flag" oficialmente soportado por React: el if
 * deja de entrar tras el segundo render porque {@code isAuthenticated} ya es true. Se usa así
 * (en lugar de useEffect) para que la sesión esté disponible ANTES de que ProtectedRoute
 * evalúe su guard.
 */
function PrimeSession({ children }: { children: React.ReactNode }) {
  const { setSession, isAuthenticated } = useAuth();
  if (!isAuthenticated) {
    setSession('jwt.signed.token', USER);
    return null;
  }
  return <>{children}</>;
}

describe('<ProtectedRoute />', () => {
  it('redirige a /login cuando no hay sesión', () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider>
          <Routes>
            <Route
              path="/dashboard"
              element={
                <ProtectedRoute>
                  <div data-testid="protected">SECRET</div>
                </ProtectedRoute>
              }
            />
            <Route path="/login" element={<div data-testid="login">LOGIN</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(screen.getByTestId('login')).toBeInTheDocument();
    expect(screen.queryByTestId('protected')).not.toBeInTheDocument();
  });

  it('renderiza children cuando hay sesión', async () => {
    render(
      <MemoryRouter initialEntries={['/dashboard']}>
        <AuthProvider>
          <PrimeSession>
            <Routes>
              <Route
                path="/dashboard"
                element={
                  <ProtectedRoute>
                    <div data-testid="protected">SECRET</div>
                  </ProtectedRoute>
                }
              />
              <Route path="/login" element={<div data-testid="login">LOGIN</div>} />
            </Routes>
          </PrimeSession>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByTestId('protected')).toBeInTheDocument();
  });
});
