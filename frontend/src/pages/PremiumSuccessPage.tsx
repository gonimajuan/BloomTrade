import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Sparkles } from 'lucide-react';
import { AppHeader } from '@/components/AppHeader';
import { useSubscription } from '@/features/subscription/hooks/useSubscription';
import { Card } from '@/components/ui/Card';

/**
 * Página /premium/success — Stripe redirige aquí tras el checkout exitoso (HU-F06 §5.1 paso 19).
 * Revamp Lote E: dark glass + framer scale-in + Sparkles violet.
 */
export function PremiumSuccessPage() {
  const [params] = useSearchParams();
  const sessionId = params.get('session_id');
  const { data } = useSubscription({ polling: true });
  const navigate = useNavigate();

  const isActive = data?.subscription?.status === 'ACTIVE';

  useEffect(() => {
    if (!isActive) return;
    const timer = setTimeout(() => navigate('/dashboard', { replace: true }), 3_000);
    return () => clearTimeout(timer);
  }, [isActive, navigate]);

  return (
    <>
      <AppHeader />
      <main className="mx-auto flex max-w-xl items-center justify-center px-6 py-16">
        <motion.div
          initial={{ opacity: 0, scale: 0.95, y: 16 }}
          animate={{ opacity: 1, scale: 1, y: 0 }}
          transition={{ duration: 0.5, ease: 'easeOut' }}
          className="w-full"
        >
          <Card variant="glass-elevated" className="p-10 text-center">
            {isActive ? (
              <>
                <div className="mb-4 inline-flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/15 ring-1 ring-emerald-500/30">
                  <Sparkles className="h-7 w-7 text-emerald-300" aria-hidden />
                </div>
                <h1 className="text-3xl font-semibold tracking-tight text-white">
                  ¡Bienvenido a Premium!
                </h1>
                <p className="mt-3 text-sm text-slate-300">
                  Tu suscripción está activa. Te redirigimos a tu dashboard…
                </p>
              </>
            ) : (
              <>
                <div className="mb-4 inline-flex h-14 w-14 items-center justify-center rounded-full bg-violet-500/15 ring-1 ring-violet-500/30">
                  <Sparkles className="h-7 w-7 text-violet-300" aria-hidden />
                </div>
                <h1 className="text-2xl font-semibold tracking-tight text-white">
                  Procesando tu pago…
                </h1>
                <p className="mt-3 text-sm text-slate-300">
                  Esto suele tomar unos segundos. Si tarda más, te avisaremos por email
                  cuando se complete.
                </p>
                <p className="mt-6 text-xs text-slate-500">
                  Session ID:{' '}
                  <code className="font-mono text-slate-400">
                    {sessionId ?? '(no disponible)'}
                  </code>
                </p>
                <Link
                  to="/dashboard"
                  className="mt-6 inline-block text-sm font-medium text-violet-300 underline-offset-4 transition-colors hover:text-violet-200 hover:underline"
                >
                  Ir a mi dashboard
                </Link>
              </>
            )}
          </Card>
        </motion.div>
      </main>
    </>
  );
}
