import { useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { motion, type Variants } from 'framer-motion';
import { AppHeader } from '@/components/AppHeader';
import { EquityCard } from '@/features/dashboard/components/EquityCard';
import { RecentOrdersWidget } from '@/features/dashboard/components/RecentOrdersWidget';
import { SparklinePanel } from '@/features/dashboard/components/SparklinePanel';
import { TickerGrid } from '@/features/dashboard/components/TickerGrid';
import { useDashboardSnapshot } from '@/features/dashboard/hooks/useDashboardSnapshot';
import { MarketDataBanner } from '@/features/portfolio/components/MarketDataBanner';
import { Card } from '@/components/ui/Card';
import { dashboardMessages } from '@/lib/messages.es';
import type { TickerDashboardDto } from '@/types/api';

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
 * Página `/dashboard` (HU-F18 + HU-F17 widget embebido). Revamp Lote D (2026-05-27):
 * dark glass + framer stagger entry + tipografía Space Grotesk + max-w-7xl para acomodar
 * grid 5×5 de mercados sin overflow.
 */
export function DashboardPage() {
  const queryClient = useQueryClient();
  const snapshot = useDashboardSnapshot();
  const [selectedTicker, setSelectedTicker] = useState<string | null>(null);

  const handleRefresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
  };

  const allItems = useMemo<TickerDashboardDto[]>(() => {
    if (!snapshot.data) return [];
    return snapshot.data.tickers.flatMap((g) => g.items);
  }, [snapshot.data]);

  const effectiveSelected =
    selectedTicker ??
    allItems.find((it) => it.sparkline.length > 0)?.ticker ??
    allItems[0]?.ticker ??
    null;

  const selectedItem =
    effectiveSelected === null
      ? null
      : (allItems.find((it) => it.ticker === effectiveSelected) ?? null);

  return (
    <>
      <AppHeader />
      <main className="mx-auto max-w-7xl px-6 py-10">
        <header className="mb-8">
          <h1 className="text-3xl font-semibold tracking-tight text-white">
            {dashboardMessages.title}
          </h1>
          <p className="mt-1.5 text-sm text-slate-400">
            Resumen general de tu cuenta y mercados.
          </p>
        </header>

        {snapshot.isLoading ? (
          <Card variant="glass" className="p-10 text-center text-sm text-slate-400">
            Cargando dashboard…
          </Card>
        ) : snapshot.error ? (
          <Card
            variant="glass"
            className="border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
          >
            No se pudo cargar el dashboard: {snapshot.error.message}
          </Card>
        ) : snapshot.data ? (
          <motion.div
            variants={container}
            initial="hidden"
            animate="show"
            className="space-y-6"
          >
            <motion.div variants={item}>
              <EquityCard
                equity={snapshot.data.equity}
                fetchedAt={snapshot.data.fetchedAt}
                onRefresh={handleRefresh}
                isFetching={snapshot.isFetching}
              />
            </motion.div>
            <motion.div variants={item}>
              <MarketDataBanner status={snapshot.data.marketDataAvailable} />
            </motion.div>
            <motion.div variants={item}>
              <TickerGrid
                tickers={snapshot.data.tickers}
                selectedTicker={effectiveSelected}
                onSelectTicker={setSelectedTicker}
              />
            </motion.div>
            <motion.div variants={item}>
              <SparklinePanel ticker={selectedItem} />
            </motion.div>
            <motion.div variants={item}>
              <RecentOrdersWidget />
            </motion.div>
          </motion.div>
        ) : null}
      </main>
    </>
  );
}
