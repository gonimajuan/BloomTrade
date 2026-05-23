import { useEffect } from 'react';
import type { OrderResponse } from '@/types/api';

const AUTO_DISMISS_MS = 6_000;

interface Props {
  order: OrderResponse;
  isIdempotent: boolean;
  onClose: () => void;
}

/**
 * Toast efímero que confirma la ejecución (SPEC §12.1 paso 5).
 * Auto-dismiss en {@link AUTO_DISMISS_MS}ms. El padre controla la lifecycle via {@code onClose}.
 */
export function OrderConfirmationToast({ order, isIdempotent, onClose }: Props) {
  useEffect(() => {
    const handle = window.setTimeout(onClose, AUTO_DISMISS_MS);
    return () => window.clearTimeout(handle);
  }, [onClose]);

  const isQueued = order.status === 'PENDING';
  const unitPrice = order.executionUnitPrice ?? order.quotedUnitPrice;
  const total = order.executionTotal ?? order.quotedTotal ?? '—';
  const headline = isIdempotent
    ? 'Tu orden ya estaba registrada'
    : isQueued
      ? `Orden en cola: ${order.quantity} ${order.ticker}`
      : `Orden ejecutada: ${order.quantity} ${order.ticker}`;
  const icon = isQueued ? '⏳' : '✅';

  // Paleta: emerald (verde) para EXECUTED; amber (ámbar) para PENDING/QUEUED.
  const palette = isQueued
    ? {
        border: 'border-amber-300',
        bg: 'bg-amber-50',
        title: 'text-amber-900',
        body: 'text-amber-800',
        meta: 'text-amber-700',
        close: 'text-amber-700 hover:text-amber-900',
      }
    : {
        border: 'border-emerald-300',
        bg: 'bg-emerald-50',
        title: 'text-emerald-900',
        body: 'text-emerald-800',
        meta: 'text-emerald-700',
        close: 'text-emerald-700 hover:text-emerald-900',
      };

  return (
    <div
      role="status"
      aria-live="polite"
      className={`fixed right-6 top-6 z-50 w-[20rem] rounded-lg border ${palette.border} ${palette.bg} p-4 shadow-lg`}
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className={`text-sm font-semibold ${palette.title}`}>
            {icon} {headline}
          </p>
          {isQueued ? (
            <p className={`mt-1 text-xs ${palette.body}`}>
              Mercado cerrado. Se ejecutará al abrir, al mejor precio disponible.
              Saldo reservado: USD ${total}.
            </p>
          ) : (
            <p className={`mt-1 text-xs ${palette.body}`}>
              Precio unitario: USD ${unitPrice} · Total: USD ${total}
            </p>
          )}
          {order.alpacaOrderId && (
            <p className={`mt-1 text-[10px] ${palette.meta}`}>
              Alpaca ID: {order.alpacaOrderId}
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Cerrar"
          className={`rounded text-sm ${palette.close}`}
        >
          ×
        </button>
      </div>
    </div>
  );
}
