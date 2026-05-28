import { Link, Navigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { RegisterForm } from '@/features/auth/components/RegisterForm';
import { useAuth } from '@/features/auth/context/AuthContext';
import { Card } from '@/components/ui/Card';

/** Página /register (spec HU-F01 §12.1). Guard de sesión real desde HU-F02 (Lote G). */
export function RegisterPage() {
  const { isAuthenticated } = useAuth();
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
        <div className="mb-6 text-center">
          <div className="mb-3 inline-flex items-center gap-2">
            <span aria-hidden className="text-3xl text-violet-400">
              ❖
            </span>
            <span className="text-2xl font-semibold tracking-tight text-white">
              BloomTrade
            </span>
          </div>
          <p className="text-sm text-slate-400">
            Operá en 5 mercados internacionales con USD 10.000 de práctica.
          </p>
        </div>

        <Card variant="glass-elevated" className="p-8">
          <header className="mb-6">
            <h1 className="text-xl font-semibold text-white">Crear cuenta</h1>
            <p className="mt-1 text-sm text-slate-400">
              Tomá unos segundos y empezá a operar.
            </p>
          </header>

          <RegisterForm />

          <p className="mt-6 text-center text-sm text-slate-400">
            ¿Ya tenés cuenta?{' '}
            <Link
              to="/login"
              className="font-medium text-violet-300 underline-offset-4 transition-colors hover:text-violet-200 hover:underline"
            >
              Iniciá sesión
            </Link>
          </p>
        </Card>
      </motion.div>
    </main>
  );
}
