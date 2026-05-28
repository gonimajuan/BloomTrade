import { useState } from 'react';
import { motion } from 'framer-motion';
import { Sparkles } from 'lucide-react';
import { AppHeader } from '@/components/AppHeader';
import { useSubscription } from '@/features/subscription/hooks/useSubscription';
import { useStartCheckout } from '@/features/subscription/hooks/useStartCheckout';
import { useOpenBillingPortal } from '@/features/subscription/hooks/useOpenBillingPortal';
import { humanFor } from '@/lib/messages.es';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';
import { Badge } from '@/components/ui/Badge';
import { cn } from '@/lib/cn';
import type { BillingPlan, SubscriptionDto } from '@/types/api';

/**
 * Página de gestión de suscripción premium (HU-F06 §12.1, v1.2).
 * Revamp Lote E: dark glass + Cards semánticas + Buttons primitives + plan highlighted con violet glow.
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
      <>
        <AppHeader />
        <main className="mx-auto max-w-3xl px-6 py-10 text-sm text-slate-400">
          Cargando información de suscripción…
        </main>
      </>
    );
  }

  const subscription = data?.subscription ?? null;
  const isPremium = data?.isPremium ?? false;

  return (
    <>
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-10">
        <motion.header
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, ease: 'easeOut' }}
          className="mb-8"
        >
          <h1 className="text-3xl font-semibold tracking-tight text-white">Mi plan</h1>
          <p className="mt-1.5 text-sm text-slate-400">
            Gestiona tu suscripción y desbloquea funcionalidades premium.
          </p>
        </motion.header>

        {errorBanner && (
          <Card
            variant="glass"
            role="alert"
            className="mb-6 border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
          >
            {errorBanner}
          </Card>
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
    </>
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
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      <Card
        variant="glass-elevated"
        className="border-emerald-500/30 bg-emerald-500/5 p-6"
      >
        <div className="mb-3 flex items-center gap-2">
          <Sparkles className="h-5 w-5 text-emerald-300" aria-hidden />
          <h2 className="text-lg font-semibold text-emerald-200">Eres usuario Premium.</h2>
        </div>
        <p className="text-sm text-emerald-100/80">
          Plan{' '}
          <strong className="text-white">
            {subscription.plan === 'MONTHLY' ? 'Mensual' : 'Anual'}
          </strong>
          . Tu próximo cargo es el{' '}
          <strong className="text-white">{periodEnd}</strong>.
        </p>
        <Button
          variant="subtle"
          size="md"
          onClick={onManage}
          disabled={disabled}
          className="mt-4"
        >
          Gestionar suscripción
        </Button>
      </Card>
    </motion.div>
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
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      <Card
        variant="glass-elevated"
        className="border-amber-500/30 bg-amber-500/5 p-6"
      >
        <h2 className="text-lg font-semibold text-amber-200">
          Tu suscripción terminará el {periodEnd}
        </h2>
        <p className="mt-1 text-sm text-amber-100/80">
          Mantenés acceso premium hasta esa fecha. Podés reactivar tu suscripción en
          cualquier momento desde el portal de pagos.
        </p>
        <Button
          variant="subtle"
          size="md"
          onClick={onManage}
          disabled={disabled}
          className="mt-4"
        >
          Gestionar suscripción
        </Button>
      </Card>
    </motion.div>
  );
}

function TerminalBanner({ subscription }: { subscription: SubscriptionDto }) {
  const cancelled = subscription.status === 'CANCELLED';
  return (
    <motion.div
      initial={{ opacity: 0, y: 16 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4, ease: 'easeOut' }}
    >
      <Card
        variant="glass"
        className={cn(
          'p-6',
          cancelled
            ? 'border-slate-500/30 bg-slate-700/20'
            : 'border-rose-500/30 bg-rose-500/10',
        )}
      >
        <h2
          className={cn(
            'text-lg font-semibold',
            cancelled ? 'text-slate-200' : 'text-rose-200',
          )}
        >
          {cancelled
            ? 'Tu suscripción premium terminó'
            : 'Tu pago falló y perdiste el acceso premium'}
        </h2>
        <p className="mt-1 text-sm text-slate-300">
          Podés re-suscribirte cuando quieras eligiendo uno de los planes a continuación.
        </p>
      </Card>
    </motion.div>
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
      <h2 className="text-xs font-medium uppercase tracking-[0.2em] text-slate-400">
        Activá BloomTrade Premium
      </h2>
      <div className="mt-5 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <PlanCard
          title="Plan Mensual"
          price="USD $12"
          period="/ mes"
          ctaLabel="Activar mensual"
          onClick={() => onSelectPlan('MONTHLY')}
          disabled={disabled}
        />
        <PlanCard
          title="Plan Anual"
          price="USD $120"
          period="/ año"
          savings="Equivalente a $10/mes — ahorrás $24"
          ctaLabel="Activar anual"
          onClick={() => onSelectPlan('YEARLY')}
          disabled={disabled}
          highlighted
        />
      </div>
      <ul className="mt-6 space-y-1.5 text-sm text-slate-400">
        <li className="flex items-start gap-2">
          <Sparkles className="mt-0.5 h-3.5 w-3.5 text-violet-400" aria-hidden />
          Alertas de precio personalizadas (próximamente)
        </li>
        <li className="flex items-start gap-2">
          <Sparkles className="mt-0.5 h-3.5 w-3.5 text-violet-400" aria-hidden />
          Watchlist con notificaciones (próximamente)
        </li>
      </ul>
    </section>
  );
}

function PlanCard({
  title,
  price,
  period,
  savings,
  ctaLabel,
  onClick,
  disabled,
  highlighted = false,
}: {
  title: string;
  price: string;
  period: string;
  savings?: string;
  ctaLabel: string;
  onClick: () => void;
  disabled: boolean;
  highlighted?: boolean;
}) {
  return (
    <Card
      variant={highlighted ? 'glass-elevated' : 'glass'}
      className={cn(
        'relative p-6',
        highlighted && 'border-violet-500/40 shadow-glow-violet',
      )}
    >
      {highlighted && (
        <Badge variant="accent" className="absolute -top-2 right-4">
          Más popular
        </Badge>
      )}
      <h3 className="text-base font-semibold text-white">{title}</h3>
      <div className="mt-2 flex items-baseline gap-1.5">
        <p className="text-3xl font-bold tabular-nums text-white">{price}</p>
        <p className="text-sm text-slate-400">{period}</p>
      </div>
      {savings && <p className="mt-1 text-xs text-emerald-300">{savings}</p>}
      <Button
        variant={highlighted ? 'primary' : 'subtle'}
        size="md"
        onClick={onClick}
        disabled={disabled}
        isLoading={disabled}
        className="mt-5 w-full"
      >
        {disabled ? 'Procesando…' : ctaLabel}
      </Button>
    </Card>
  );
}
