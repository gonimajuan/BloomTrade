import { useState } from 'react';
import { AppHeader } from '@/components/AppHeader';
import { useSubscription } from '@/features/subscription/hooks/useSubscription';
import { useStartCheckout } from '@/features/subscription/hooks/useStartCheckout';
import { useOpenBillingPortal } from '@/features/subscription/hooks/useOpenBillingPortal';
import { humanFor } from '@/lib/messages.es';
import type { BillingPlan, SubscriptionDto } from '@/types/api';

/**
 * Página de gestión de suscripción premium (HU-F06 §12.1, v1.2).
 *
 * Orquesta 4 estados:
 *  A — sin suscripción: cards de planes (mensual/anual)
 *  B — ACTIVE sin cancel_at_period_end: banner verde + "Gestionar suscripción" (→ Portal)
 *  C — ACTIVE con cancel_at_period_end=true: banner amarillo + "Gestionar suscripción"
 *  D — CANCELLED / PAST_DUE: banner gris/rojo + cards de planes (re-suscribirse)
 */
export function PremiumPage() {
  const { data, isLoading } = useSubscription();
  const startCheckout = useStartCheckout();
  const openPortal = useOpenBillingPortal();
  const [errorBanner, setErrorBanner] = useState<string | null>(null);

  const handleSelectPlan = (plan: BillingPlan) => {
    setErrorBanner(null);
    startCheckout.mutate(plan, {
      onError: (err) => setErrorBanner(err.message ?? humanFor(err.code)),
    });
  };

  const handleOpenPortal = () => {
    setErrorBanner(null);
    openPortal.mutate(undefined, {
      onError: (err) => setErrorBanner(err.message ?? humanFor(err.code)),
    });
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50">
        <AppHeader />
        <main className="mx-auto max-w-3xl px-6 py-10 text-slate-500">
          Cargando información de suscripción…
        </main>
      </div>
    );
  }

  const subscription = data?.subscription ?? null;
  const isPremium = data?.isPremium ?? false;

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-10">
        <h1 className="text-2xl font-semibold text-slate-900">Mi plan</h1>

        {errorBanner && (
          <div
            role="alert"
            className="mt-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            {errorBanner}
          </div>
        )}

        {isPremium && subscription && !subscription.cancelAtPeriodEnd && (
          <ActiveBanner
            subscription={subscription}
            onManage={handleOpenPortal}
            disabled={openPortal.isPending}
          />
        )}

        {isPremium && subscription && subscription.cancelAtPeriodEnd && (
          <CancelScheduledBanner
            subscription={subscription}
            onManage={handleOpenPortal}
            disabled={openPortal.isPending}
          />
        )}

        {!isPremium && subscription && (
          <TerminalBanner subscription={subscription} />
        )}

        {!isPremium && (
          <PlansSection
            onSelectPlan={handleSelectPlan}
            disabled={startCheckout.isPending}
          />
        )}
      </main>
    </div>
  );
}

function ActiveBanner({
  subscription,
  onManage,
  disabled,
}: {
  subscription: SubscriptionDto;
  onManage: () => void;
  disabled: boolean;
}) {
  const periodEnd = new Date(subscription.currentPeriodEnd).toLocaleDateString();
  return (
    <section className="mt-6 rounded-lg border border-emerald-200 bg-emerald-50 p-6">
      <h2 className="text-lg font-semibold text-emerald-900">Eres Premium 🌟</h2>
      <p className="mt-1 text-sm text-emerald-800">
        Plan <strong>{subscription.plan === 'MONTHLY' ? 'Mensual' : 'Anual'}</strong>. Tu próximo
        cargo es el <strong>{periodEnd}</strong>.
      </p>
      <button
        type="button"
        onClick={onManage}
        disabled={disabled}
        className="mt-4 rounded-md border border-emerald-300 bg-white px-4 py-2 text-sm font-medium text-emerald-800 hover:bg-emerald-100 disabled:opacity-40"
      >
        Gestionar suscripción
      </button>
    </section>
  );
}

function CancelScheduledBanner({
  subscription,
  onManage,
  disabled,
}: {
  subscription: SubscriptionDto;
  onManage: () => void;
  disabled: boolean;
}) {
  const periodEnd = new Date(subscription.currentPeriodEnd).toLocaleDateString();
  return (
    <section className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-6">
      <h2 className="text-lg font-semibold text-amber-900">
        Tu suscripción terminará el {periodEnd}
      </h2>
      <p className="mt-1 text-sm text-amber-800">
        Mantienes acceso premium hasta esa fecha. Puedes reactivar tu suscripción en cualquier
        momento desde el portal de pagos.
      </p>
      <button
        type="button"
        onClick={onManage}
        disabled={disabled}
        className="mt-4 rounded-md border border-amber-300 bg-white px-4 py-2 text-sm font-medium text-amber-800 hover:bg-amber-100 disabled:opacity-40"
      >
        Gestionar suscripción
      </button>
    </section>
  );
}

function TerminalBanner({ subscription }: { subscription: SubscriptionDto }) {
  const cancelled = subscription.status === 'CANCELLED';
  return (
    <section
      className={`mt-6 rounded-lg border p-6 ${
        cancelled
          ? 'border-slate-200 bg-slate-100'
          : 'border-red-200 bg-red-50'
      }`}
    >
      <h2
        className={`text-lg font-semibold ${cancelled ? 'text-slate-700' : 'text-red-800'}`}
      >
        {cancelled
          ? 'Tu suscripción premium terminó'
          : 'Tu pago falló y perdiste el acceso premium'}
      </h2>
      <p className="mt-1 text-sm text-slate-700">
        Puedes re-suscribirte cuando quieras eligiendo uno de los planes a continuación.
      </p>
    </section>
  );
}

function PlansSection({
  onSelectPlan,
  disabled,
}: {
  onSelectPlan: (plan: BillingPlan) => void;
  disabled: boolean;
}) {
  return (
    <section className="mt-8">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
        Activa BloomTrade Premium
      </h2>
      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <PlanCard
          title="Plan Mensual"
          price="USD $12 / mes"
          ctaLabel="Activar mensual"
          onClick={() => onSelectPlan('MONTHLY')}
          disabled={disabled}
        />
        <PlanCard
          title="Plan Anual"
          price="USD $120 / año"
          savings="Equivalente a $10/mes — ahorra $24"
          ctaLabel="Activar anual"
          onClick={() => onSelectPlan('YEARLY')}
          disabled={disabled}
          highlighted
        />
      </div>
      <ul className="mt-6 list-inside list-disc text-sm text-slate-600">
        <li>Alertas de precio personalizadas (próximamente)</li>
        <li>Watchlist con notificaciones (próximamente)</li>
      </ul>
    </section>
  );
}

function PlanCard({
  title,
  price,
  savings,
  ctaLabel,
  onClick,
  disabled,
  highlighted = false,
}: {
  title: string;
  price: string;
  savings?: string;
  ctaLabel: string;
  onClick: () => void;
  disabled: boolean;
  highlighted?: boolean;
}) {
  return (
    <div
      className={`rounded-lg border p-6 ${
        highlighted ? 'border-emerald-300 bg-emerald-50' : 'border-slate-200 bg-white'
      }`}
    >
      <h3 className="text-base font-semibold text-slate-900">{title}</h3>
      <p className="mt-1 text-xl font-bold text-slate-900">{price}</p>
      {savings && <p className="mt-1 text-xs text-emerald-700">{savings}</p>}
      <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        className="mt-4 w-full rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-40"
      >
        {disabled ? 'Procesando…' : ctaLabel}
      </button>
    </div>
  );
}
