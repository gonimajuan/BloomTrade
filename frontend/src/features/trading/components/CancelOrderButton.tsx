import { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import {
  buildCancelSuccessMessage,
  useCancelOrder,
} from '@/features/trading/hooks/useCancelOrder';
import { humanFor } from '@/lib/messages.es';
import { Button } from '@/components/ui/Button';
import { Modal } from '@/components/ui/Modal';

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
 * HU-F15 — botón "Cancelar" con Modal de confirmación + toast (sonner) feedback.
 * Si {@code cancelRequestedAt} está seteado, renderea disabled con label "Cancelando…"
 * + spinner. Click → Modal con texto side-aware → mutate → toast success/error.
 *
 * <p>Revamp UI Lote B (2026-05-27): reemplazado {@code window.confirm/alert} por
 * {@link Modal} primitive + {@code sonner.toast} (cierra deuda viva #33 D35).
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
  const [isModalOpen, setIsModalOpen] = useState(false);

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

  const handleConfirm = () => {
    setIsModalOpen(false);
    mutate(orderId, {
      onSuccess: (response) => {
        toast.success(buildCancelSuccessMessage(response));
      },
      onError: (error) => {
        const msg =
          error.message ||
          humanFor(error.code) ||
          'No pudimos procesar tu solicitud. Intenta nuevamente.';
        toast.error(msg);
      },
    });
  };

  return (
    <>
      <Button
        variant="destructive"
        size="sm"
        isLoading={isPending}
        onClick={() => setIsModalOpen(true)}
      >
        {isPending ? 'Cancelando…' : 'Cancelar'}
      </Button>
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title="Cancelar orden"
        size="sm"
      >
        <p className="mb-6 text-sm text-slate-300">{confirmMessage}</p>
        <div className="flex justify-end gap-2">
          <Button
            variant="ghost"
            size="md"
            onClick={() => setIsModalOpen(false)}
          >
            Mantener orden
          </Button>
          <Button variant="destructive" size="md" onClick={handleConfirm}>
            Cancelar orden
          </Button>
        </div>
      </Modal>
    </>
  );
}
