import { Link, Navigate } from 'react-router-dom';
import { RegisterForm } from '@/features/auth/components/RegisterForm';
import { useSession } from '@/features/auth/hooks/useSession';

/** Página /register (spec HU-F01 §12.1). Guard de sesión inerte hasta HU-F02. */
export function RegisterPage() {
  const { token } = useSession();
  if (token) {
    return <Navigate to="/dashboard" replace />;
  }

  return (
    <main className="min-h-screen bg-slate-950 px-4 py-12 text-slate-100">
      <div className="mx-auto w-full max-w-md rounded-xl bg-slate-900/60 p-8 shadow-2xl ring-1 ring-slate-800">
        <header className="mb-6 text-center">
          <h1 className="text-2xl font-bold">Crear cuenta en BloomTrade</h1>
          <p className="mt-1 text-sm text-slate-400">
            Operá en los 5 mercados internacionales con un saldo de práctica de USD 10.000.
          </p>
        </header>
        <RegisterForm />
        <p className="mt-6 text-center text-sm text-slate-400">
          ¿Ya tenés cuenta?{' '}
          <Link to="/login" className="text-blue-400 hover:text-blue-300">
            Iniciá sesión
          </Link>
        </p>
      </div>
    </main>
  );
}
