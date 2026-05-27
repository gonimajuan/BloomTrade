import { Loader2 } from 'lucide-react';
import {
  buildCancelSuccessMessage,
  useCancelOrder,
} from '@/features/trading/hooks/useCancelOrder';
import { humanFor } from '@/lib/messages.es';

/**
 * Props para {@link CancelOrderButton} — pensado para reuso desde PendingOrdersPanel
 * (HU-F16 pendingOrders[]) y RecentOrdersWidget (HU-F17 history). Solo se renderea cuando
 * la orden está en estado cancelable (PENDING + alpacaOrderId).
 */
interface Props {
  orderId: string;
  side: 'BUY' | 'SELL';
  quantity: number;
  ticker: string;
  /** Monto que se refundará al saldo (BUY) — opcional, para construir el confirm dialog. */
  quotedTotal?: string | null;
  /** Si el cancel ya fue solicitado pero quedó en polling-timeout (reconcile lo materializará). */
  cancelRequestedAt?: string | null;
}

/**
 * HU-F15 — botón "Cancelar" con confirm dialog + visual feedback polling-timeout
 * (D10 + D11 SPEC). Si {@code cancelRequestedAt} está seteado, renderea disabled con label
 * "Cancelando…" + spinner. Click → window.confirm con texto side-aware → mutate.
 *
 * <p>Feedback éxito/error: window.alert para MVP (sin sistema toast global — registrado como
 * deuda D35 D-NO-TOAST-SYSTEM en plan.md, deferido a revamp UI). El refetch granular del
 * hook actualiza balance/positions/recentOrders en background.
 */
export function CancelOrderButton({
  orderId,
  side,
  quantity,
  ticker,
  quotedTotal,
  cancelRequestedAt,
}: Props) {
  const { mutate, isPending } = useCancelOrder();

  // Visual feedback polling-timeout (D11): fila ya marcada como "Cancelando…" — disabled.
  if (cancelRequestedAt) {
    return (
      <div
        className="inline-flex items-center gap-1.5 text-xs text-slate-500"
        aria-busy="true"
      >
        <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
        <span>Cancelando…</span>
      </div>
    );
  }

  const confirmMessage =
    side === 'BUY'
      ? `¿Cancelar tu orden de compra de ${quantity} ${ticker}?` +
        (quotedTotal ? ` Se restaurarán USD ${quotedTotal} a tu saldo.` : '')
      : `¿Cancelar tu orden de venta de ${quantity} ${ticker}? Se restaurarán ${quantity} acciones a tu posición.`;

  const handleClick = () => {
    if (!window.confirm(confirmMessage)) return;
    mutate(orderId, {
      onSuccess: (response) => {
        // MVP: window.alert. Deuda D35: reemplazar por toast global post revamp UI.
        window.alert(buildCancelSuccessMessage(response));
      },
      onError: (error) => {
        // ParsedError ya trae message humano via humanFor() del messages.es.ts.
        const msg =
          error.message ||
          humanFor(error.code) ||
          'No pudimos procesar tu solicitud. Intenta nuevamente.';
        window.alert(`Error: ${msg}`);
      },
    });
  };

  return (
    <button
      type="button"
      disabled={isPending}
      onClick={handleClick}
      className="inline-flex items-center gap-1 rounded-md border border-slate-300 px-2 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-50 disabled:cursor-not-allowed"
    >
      {isPending ? (
        <>
          <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />
          <span>Cancelando…</span>
        </>
      ) : (
        'Cancelar'
      )}
    </button>
  );
}
