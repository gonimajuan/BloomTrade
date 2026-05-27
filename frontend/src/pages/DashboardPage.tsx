import { useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AppHeader } from '@/components/AppHeader';
import { EquityCard } from '@/features/dashboard/components/EquityCard';
import { RecentOrdersWidget } from '@/features/dashboard/components/RecentOrdersWidget';
import { SparklinePanel } from '@/features/dashboard/components/SparklinePanel';
import { TickerGrid } from '@/features/dashboard/components/TickerGrid';
import { useDashboardSnapshot } from '@/features/dashboard/hooks/useDashboardSnapshot';
import { MarketDataBanner } from '@/features/portfolio/components/MarketDataBanner';
import { dashboardMessages } from '@/lib/messages.es';
import type { TickerDashboardDto } from '@/types/api';

/**
 * Página `/dashboard` (HU-F18 + HU-F17 widget embebido). Layout vertical:
 *  1. EquityCard arriba con equity total + P&L no realizado + botón refresh.
 *  2. MarketDataBanner condicional (reuso del componente F16).
 *  3. TickerGrid responsive con las 25 acciones agrupadas por mercado + sparklines.
 *  4. RecentOrdersWidget colapsable con últimas 10 órdenes (HU-F17).
 *
 * Plan C5 + plan D8: polling 30s + botón refresh manual que invalida ambas queryKeys
 * (dashboard + orders) en un solo gesto.
 */
export function DashboardPage() {
  const queryClient = useQueryClient();
  const snapshot = useDashboardSnapshot();
  const [selectedTicker, setSelectedTicker] = useState<string | null>(null);

  const handleRefresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
  };

  // Flatten para lookup O(1) del ticker seleccionado, y para elegir un default sensato.
  const allItems = useMemo<TickerDashboardDto[]>(() => {
    if (!snapshot.data) return [];
    return snapshot.data.tickers.flatMap((g) => g.items);
  }, [snapshot.data]);

  const effectiveSelected =
    selectedTicker ??
    allItems.find((item) => item.sparkline.length > 0)?.ticker ??
    allItems[0]?.ticker ??
    null;

  const selectedItem =
    effectiveSelected === null
      ? null
      : (allItems.find((item) => item.ticker === effectiveSelected) ?? null);

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <main className="mx-auto max-w-6xl space-y-6 px-6 py-10">
        <header>
          <h1 className="text-2xl font-semibold text-slate-900">
            {dashboardMessages.title}
          </h1>
        </header>

        {snapshot.isLoading ? (
          <div className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
            Cargando dashboard…
          </div>
        ) : snapshot.error ? (
          <div className="rounded-md border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-900">
            No se pudo cargar el dashboard: {snapshot.error.message}
          </div>
        ) : snapshot.data ? (
          <>
            <EquityCard
              equity={snapshot.data.equity}
              fetchedAt={snapshot.data.fetchedAt}
              onRefresh={handleRefresh}
              isFetching={snapshot.isFetching}
            />
            <MarketDataBanner status={snapshot.data.marketDataAvailable} />
            <TickerGrid
              tickers={snapshot.data.tickers}
              selectedTicker={effectiveSelected}
              onSelectTicker={setSelectedTicker}
            />
            <SparklinePanel ticker={selectedItem} />
          </>
        ) : null}

        <RecentOrdersWidget />
      </main>
    </div>
  );
}
