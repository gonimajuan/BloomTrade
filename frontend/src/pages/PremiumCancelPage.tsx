import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { XCircle } from 'lucide-react';
import { AppHeader } from '@/components/AppHeader';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';

/** Página /premium/cancel — Stripe redirige aquí si el usuario abandona el checkout. */
export function PremiumCancelPage() {
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
            <div className="mb-4 inline-flex h-14 w-14 items-center justify-center rounded-full bg-slate-700/40 ring-1 ring-white/10">
              <XCircle className="h-7 w-7 text-slate-400" aria-hidden />
            </div>
            <h1 className="text-2xl font-semibold tracking-tight text-white">
              Pago no completado
            </h1>
            <p className="mt-3 text-sm text-slate-300">
              No se hizo ningún cargo a tu tarjeta. Podés volver a intentarlo cuando quieras.
            </p>
            <div className="mt-6 flex justify-center gap-2">
              <Link to="/premium">
                <Button variant="primary" size="md">
                  Volver a intentar
                </Button>
              </Link>
              <Link to="/dashboard">
                <Button variant="ghost" size="md">
                  Ir al dashboard
                </Button>
              </Link>
            </div>
          </Card>
        </motion.div>
      </main>
    </>
  );
}
