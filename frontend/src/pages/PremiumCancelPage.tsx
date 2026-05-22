import { Link } from 'react-router-dom';
import { AppHeader } from '@/components/AppHeader';

/** Página /premium/cancel — Stripe redirige aquí si el usuario abandona el checkout. */
export function PremiumCancelPage() {
  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <main className="mx-auto max-w-2xl px-6 py-10 text-center">
        <h1 className="text-2xl font-semibold text-slate-900">Pago no completado</h1>
        <p className="mt-3 text-sm text-slate-600">
          No se hizo ningún cargo a tu tarjeta. Puedes volver a intentarlo cuando quieras.
        </p>
        <div className="mt-6 flex justify-center gap-3">
          <Link
            to="/premium"
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800"
          >
            Volver a intentar
          </Link>
          <Link
            to="/dashboard"
            className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            Ir al dashboard
          </Link>
        </div>
      </main>
    </div>
  );
}
