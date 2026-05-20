import { Link, Navigate } from 'react-router-dom';
import { LoginForm } from '@/features/auth/components/LoginForm';
import { useAuth } from '@/features/auth/context/AuthContext';

/**
 * Página /login (spec HU-F02 §12.1). Reemplaza el stub introducido en HU-F01 (Lote H T7.7).
 * Si el usuario ya está autenticado, redirige a /dashboard.
 */
export function LoginPage() {
  const { isAuthenticated } = useAuth();
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
