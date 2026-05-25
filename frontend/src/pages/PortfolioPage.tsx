import { useQueryClient } from '@tanstack/react-query';
import { AppHeader } from '@/components/AppHeader';
import { BalanceCard } from '@/features/portfolio/components/BalanceCard';
import { MarketDataBanner } from '@/features/portfolio/components/MarketDataBanner';
import { PendingOrdersPanel } from '@/features/portfolio/components/PendingOrdersPanel';
import { PositionsTable } from '@/features/portfolio/components/PositionsTable';
import { useBalance } from '@/features/portfolio/hooks/useBalance';
import { usePortfolioPositions } from '@/features/portfolio/hooks/usePortfolioPositions';
import { portfolioMessages } from '@/lib/messages.es';

/**
 * Página `/portfolio` (HU-F16 + HU-F21). 3 secciones verticales (plan D6):
 *  1. BalanceCard arriba con botón refresh manual.
 *  2. MarketDataBanner condicional + PositionsTable con P&L color-coded.
 *  3. PendingOrdersPanel colapsable cuando hay órdenes encoladas.
 *
 * Plan D8: ambos hooks usan refetchOnWindowFocus por default; el botón refresh invalida
 * ambas queries vía queryClient (un solo gesto refresca saldo + posiciones + pending).
 */
export function PortfolioPage() {
  const queryClient = useQueryClient();
  const balanceQuery = useBalance();
  const positionsQuery = usePortfolioPositions();

  const handleRefresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['portfolio'] });
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <main className="mx-auto max-w-4xl space-y-6 px-6 py-10">
        <header>
          <h1 className="text-2xl font-semibold text-slate-900">
            {portfolioMessages.title}
          </h1>
        </header>

        <BalanceCard
          data={balanceQuery.data}
          isLoading={balanceQuery.isLoading}
          isFetching={balanceQuery.isFetching || positionsQuery.isFetching}
          onRefresh={handleRefresh}
        />

        {balanceQuery.error && (
          <div className="rounded-md border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-900">
            No se pudo cargar el saldo: {balanceQuery.error.message}
          </div>
        )}

        {positionsQuery.isLoading ? (
          <div className="rounded-lg border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
            Cargando posiciones…
          </div>
        ) : positionsQuery.error ? (
          <div className="rounded-md border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-900">
            No se pudieron cargar las posiciones: {positionsQuery.error.message}
          </div>
        ) : positionsQuery.data ? (
          <div className="space-y-4">
            <MarketDataBanner status={positionsQuery.data.marketDataAvailable} />
            <PositionsTable positions={positionsQuery.data.positions} />
            <PendingOrdersPanel orders={positionsQuery.data.pendingOrders} />
          </div>
        ) : null}
      </main>
    </div>
  );
}
