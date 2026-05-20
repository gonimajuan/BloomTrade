import { Navigate, useLocation } from 'react-router-dom';
import { type ReactElement } from 'react';
import { useAuth } from '@/features/auth/context/AuthContext';

/**
 * Wrapper de rutas autenticadas (spec HU-F02 §12.2). Si {@code isAuthenticated} es {@code false}
 * redirige a {@code /login} preservando la ruta intentada en {@code location.state.from} para que
 * la página de login pueda devolverte al lugar correcto post-MFA (futuro post-MVP — hoy redirige
 * siempre a /dashboard).
 */
export function ProtectedRoute({ children }: { children: ReactElement }) {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return children;
}
