import { Link, Navigate, useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';
import { LoginForm } from '@/features/auth/components/LoginForm';
import { useAuth } from '@/features/auth/context/AuthContext';
import { Card } from '@/components/ui/Card';

/**
 * Página /login (spec HU-F02 §12.1). Si el usuario ya está autenticado, redirige a /dashboard.
 *
 * <p>Cuando el usuario llega aquí por kickout de sesión (interceptor 401 en apiClient), el
 * AuthContext pasa {@code state.reason='session_expired'} en el navigate; mostramos un banner
 * persistente hasta que envíe el form.
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
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: 'easeOut' }}
        className="w-full max-w-md"
      >
        <div className="mb-8 text-center">
          <div className="mb-3 inline-flex items-center gap-2">
            <span aria-hidden className="text-3xl text-violet-400">
              ❖
            </span>
            <span className="text-2xl font-semibold tracking-tight text-white">
              BloomTrade
            </span>
          </div>
          <p className="text-sm text-slate-400">
            Tu plataforma de Day Trading multimercado.
          </p>
        </div>

        <Card variant="glass-elevated" className="p-8">
          <header className="mb-6">
            <h1 className="text-xl font-semibold text-white">Iniciá sesión</h1>
            <p className="mt-1 text-sm text-slate-400">
              Ingresá tus credenciales para continuar.
            </p>
          </header>

          {sessionExpired && (
            <div
              role="status"
              className="mb-4 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-sm text-amber-200"
            >
              Tu sesión expiró. Iniciá sesión de nuevo para continuar.
            </div>
          )}

          <LoginForm />

          <p className="mt-6 text-center text-sm text-slate-400">
            ¿No tenés cuenta?{' '}
            <Link
              to="/register"
              className="font-medium text-violet-300 underline-offset-4 transition-colors hover:text-violet-200 hover:underline"
            >
              Registrate
            </Link>
          </p>
        </Card>
      </motion.div>
    </main>
  );
}
