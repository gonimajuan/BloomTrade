import { Link, Navigate, useLocation } from 'react-router-dom';
import { LoginForm } from '@/features/auth/components/LoginForm';
import { useAuth } from '@/features/auth/context/AuthContext';

/**
 * Página /login (spec HU-F02 §12.1). Reemplaza el stub introducido en HU-F01 (Lote H T7.7).
 * Si el usuario ya está autenticado, redirige a /dashboard.
 *
 * <p>Cuando el usuario llega aquí por kickout de sesión (interceptor 401 en apiClient), el
 * AuthContext pasa {@code state.reason='session_expired'} en el navigate; mostramos un banner
 * persistente hasta que envíe el form. Cierre de la mini-HU HU-F0X-token-rotation-logout.
 */
interface LoginLocationState {
  reason?: 'session_expired';
}

export function LoginPage() {
  const { isAuthenticated } = useAuth();
  const location = useLocation();
  const state = location.state as LoginLocationState | null;
  const sessionExpired = state?.reason === 'session_expired';

  if (isAuthenticated) {
    return <Navigate to="/dashboard" replace />;
  }

  return (
    <main className="min-h-screen bg-slate-950 px-4 py-12 text-slate-100">
      <div className="mx-auto w-full max-w-md rounded-xl bg-slate-900/60 p-8 shadow-2xl ring-1 ring-slate-800">
        <header className="mb-6 text-center">
          <h1 className="text-2xl font-bold">Iniciar sesión</h1>
          <p className="mt-1 text-sm text-slate-400">
            Ingresá tus credenciales para acceder a BloomTrade.
          </p>
        </header>
        {sessionExpired && (
          <div
            role="status"
            className="mb-4 rounded-md border border-amber-700/40 bg-amber-900/30 px-4 py-3 text-sm text-amber-100"
          >
            Tu sesión expiró. Iniciá sesión de nuevo para continuar.
          </div>
        )}
        <LoginForm />
        <p className="mt-6 text-center text-sm text-slate-400">
          ¿No tenés cuenta?{' '}
          <Link to="/register" className="text-blue-400 hover:text-blue-300">
            Registrate
          </Link>
        </p>
      </div>
    </main>
  );
}
