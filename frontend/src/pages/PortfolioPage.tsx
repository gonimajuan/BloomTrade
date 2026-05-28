import { useQueryClient } from '@tanstack/react-query';
import { motion, type Variants } from 'framer-motion';
import { AppHeader } from '@/components/AppHeader';
import { BalanceCard } from '@/features/portfolio/components/BalanceCard';
import { MarketDataBanner } from '@/features/portfolio/components/MarketDataBanner';
import { PendingOrdersPanel } from '@/features/portfolio/components/PendingOrdersPanel';
import { PositionsTable } from '@/features/portfolio/components/PositionsTable';
import { useBalance } from '@/features/portfolio/hooks/useBalance';
import { usePortfolioPositions } from '@/features/portfolio/hooks/usePortfolioPositions';
import { portfolioMessages } from '@/lib/messages.es';
import { Card } from '@/components/ui/Card';

const container: Variants = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: { staggerChildren: 0.08, delayChildren: 0.05 },
  },
};
const item: Variants = {
  hidden: { opacity: 0, y: 16 },
  show: { opacity: 1, y: 0, transition: { duration: 0.4, ease: 'easeOut' } },
};

/**
 * Página `/portfolio` (HU-F16 + HU-F21). Revamp Lote D: dark glass + framer stagger.
 */
export function PortfolioPage() {
  const queryClient = useQueryClient();
  const balanceQuery = useBalance();
  const positionsQuery = usePortfolioPositions();

  const handleRefresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['portfolio'] });
  };

  return (
    <>
      <AppHeader />
      <main className="mx-auto max-w-5xl px-6 py-10">
        <header className="mb-8">
          <h1 className="text-3xl font-semibold tracking-tight text-white">
            {portfolioMessages.title}
          </h1>
          <p className="mt-1.5 text-sm text-slate-400">
            Tu saldo, posiciones abiertas y órdenes en cola.
          </p>
        </header>

        <motion.div
          variants={container}
          initial="hidden"
          animate="show"
          className="space-y-6"
        >
          <motion.div variants={item}>
            <BalanceCard
              data={balanceQuery.data}
              isLoading={balanceQuery.isLoading}
              isFetching={balanceQuery.isFetching || positionsQuery.isFetching}
              onRefresh={handleRefresh}
            />
          </motion.div>

          {balanceQuery.error && (
            <motion.div variants={item}>
              <Card
                variant="glass"
                className="border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
              >
                <p>No se pudo cargar el saldo: {balanceQuery.error.message}</p>
                <p className="mt-1 text-xs italic text-rose-300/80">
                  Código: {balanceQuery.error.code}
                  {balanceQuery.error.traceId && ` · traceId: ${balanceQuery.error.traceId}`}
                </p>
              </Card>
            </motion.div>
          )}

          {positionsQuery.isLoading ? (
            <motion.div variants={item}>
              <Card variant="glass" className="p-10 text-center text-sm text-slate-400">
                Cargando posiciones…
              </Card>
            </motion.div>
          ) : positionsQuery.error ? (
            <motion.div variants={item}>
              <Card
                variant="glass"
                className="border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
              >
                <p>
                  No se pudieron cargar las posiciones: {positionsQuery.error.message}
                </p>
                <p className="mt-1 text-xs italic text-rose-300/80">
                  Código: {positionsQuery.error.code}
                  {positionsQuery.error.traceId &&
                    ` · traceId: ${positionsQuery.error.traceId}`}
                </p>
              </Card>
            </motion.div>
          ) : positionsQuery.data ? (
            <>
              <motion.div variants={item}>
                <MarketDataBanner status={positionsQuery.data.marketDataAvailable} />
              </motion.div>
              <motion.div variants={item}>
                <PositionsTable
                  positions={positionsQuery.data.positions}
                  isFetching={positionsQuery.isFetching}
                />
              </motion.div>
              <motion.div variants={item}>
                <PendingOrdersPanel
                  orders={positionsQuery.data.pendingOrders}
                  isFetching={positionsQuery.isFetching}
                />
              </motion.div>
            </>
          ) : null}
        </motion.div>
      </main>
    </>
  );
}
