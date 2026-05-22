import { useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { AppHeader } from '@/components/AppHeader';
import { useSubscription } from '@/features/subscription/hooks/useSubscription';

/**
 * Página /premium/success — Stripe redirige aquí tras el checkout exitoso (HU-F06 §5.1 paso 19).
 *
 * <p>Polling cada 2s al GET /subscriptions/me. Cuando aparece status=ACTIVE → muestra mensaje
 * de éxito y redirige a /dashboard tras 3s. Si tras ~30s no llega (webhook todavía no procesado
 * o falló), muestra mensaje neutral + link a /premium.
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
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <main className="mx-auto max-w-2xl px-6 py-10 text-center">
        {isActive ? (
          <>
            <h1 className="text-3xl font-bold text-emerald-900">¡Bienvenido a Premium! 🌟</h1>
            <p className="mt-3 text-sm text-slate-600">
              Tu suscripción está activa. Te redirigimos a tu dashboard…
            </p>
          </>
        ) : (
          <>
            <h1 className="text-2xl font-semibold text-slate-900">Procesando tu pago…</h1>
            <p className="mt-3 text-sm text-slate-600">
              Esto suele tomar unos segundos. Si tarda más, te avisaremos por email cuando se
              complete.
            </p>
            <p className="mt-6 text-xs text-slate-500">
              Session ID: <code className="font-mono">{sessionId ?? '(no disponible)'}</code>
            </p>
            <Link
              to="/dashboard"
              className="mt-6 inline-block text-sm text-blue-600 hover:underline"
            >
              Ir a mi dashboard
            </Link>
          </>
        )}
      </main>
    </div>
  );
}
