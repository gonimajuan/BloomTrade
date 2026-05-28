import { formatLocalDateTimeWithSeconds } from '@/lib/dateFormat';
import type { ParsedError } from '@/lib/errorParser';
import type { QuoteResponse } from '@/types/api';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { cn } from '@/lib/cn';

const fmt = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

function formatAmount(amount: string, currency: string): string {
  const n = Number(amount);
  return Number.isFinite(n) ? `${currency} $${fmt.format(n)}` : `${currency} ${amount}`;
}

function projectedBalance(quote: QuoteResponse): string {
  const before = Number(quote.userBalance);
  const total = Number(quote.estimatedTotal);
  if (!Number.isFinite(before) || !Number.isFinite(total)) {
    return formatAmount(quote.userBalance, quote.currency);
  }
  const after = quote.side === 'SELL' ? before + total : before - total;
  return `${quote.currency} $${fmt.format(after)}`;
}

interface Props {
  quote: QuoteResponse;
  onConfirm: () => void;
  onCancel: () => void;
  isSubmitting: boolean;
  submitError?: ParsedError | null;
}

/**
 * Panel que muestra el quote calculado (SPEC F09 + F10 side-aware).
 * Revamp Lote E: Card glass-elevated + Badge para side + Button primitives.
 */
export function OrderQuotePanel({
  quote,
  onConfirm,
  onCancel,
  isSubmitting,
  submitError,
}: Props) {
  const isSell = quote.side === 'SELL';
  const totalLabel = isSell ? 'Producto neto a recibir' : 'Total a descontar';
  const confirmLabel = isSubmitting
    ? 'Enviando orden…'
    : isSell
      ? 'Confirmar venta'
      : 'Confirmar compra';

  const canConfirm =
    (isSell ? quote.sufficientShares : quote.sufficientFunds) &&
    quote.marketOpen &&
    !isSubmitting;

  const blockedReason = (() => {
    if (isSell && !quote.sufficientShares) {
      return quote.userShares === 0
        ? `No tenés posición en ${quote.ticker}. Comprá primero para poder vender.`
        : `Solo tenés ${quote.userShares} ${quote.ticker} disponibles. Reducí la cantidad.`;
    }
    if (!isSell && !quote.sufficientFunds) {
      return 'Tu saldo no alcanza para esta orden. Reducí la cantidad y pedí otro quote.';
    }
    if (!quote.marketOpen) {
      return 'El mercado está cerrado en este momento.';
    }
    return null;
  })();

  const positionInfo = (() => {
    if (!isSell || !quote.sufficientShares) return null;
    if (quote.userShares === quote.quantity) {
      return `Esta venta liquidará tu posición completa en ${quote.ticker}.`;
    }
    return `Posición restante tras la venta: ${quote.userShares - quote.quantity} ${quote.ticker}.`;
  })();

  return (
    <Card variant="glass-elevated" aria-live="polite" className="p-6">
      <header className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-white">Resumen de la orden</h2>
        <Badge variant={isSell ? 'error' : 'success'}>
          {isSell ? 'Venta' : 'Compra'} · Market
        </Badge>
      </header>

      {submitError && (
        <div
          role="alert"
          className="mt-4 rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
        >
          <p>{submitError.message}</p>
          <p className="mt-1 text-xs italic text-rose-300/80">
            Código: {submitError.code}
            {submitError.traceId && ` · traceId: ${submitError.traceId}`}
          </p>
        </div>
      )}

      <dl className="mt-5 grid grid-cols-2 gap-y-2.5 text-sm">
        <dt className="text-slate-400">Ticker</dt>
        <dd className="text-right font-mono font-semibold text-white">{quote.ticker}</dd>

        <dt className="text-slate-400">Cantidad</dt>
        <dd className="text-right font-medium tabular-nums text-white">
          {quote.quantity.toLocaleString('en-US')}
        </dd>

        <dt className="text-slate-400">Precio unitario estimado</dt>
        <dd className="text-right font-medium tabular-nums text-slate-200">
          {formatAmount(quote.estimatedUnitPrice, quote.currency)}
        </dd>

        <dt className="text-slate-400">Subtotal</dt>
        <dd className="text-right font-medium tabular-nums text-slate-200">
          {formatAmount(quote.estimatedSubtotal, quote.currency)}
        </dd>

        <dt className="text-slate-400">Comisión (2%)</dt>
        <dd className="text-right font-medium tabular-nums text-slate-200">
          {formatAmount(quote.commission, quote.currency)}
        </dd>

        <dt className="border-t border-white/10 pt-3 text-slate-300">{totalLabel}</dt>
        <dd
          className={cn(
            'border-t border-white/10 pt-3 text-right text-base font-semibold tabular-nums',
            isSell ? 'text-emerald-300' : 'text-white',
          )}
        >
          {formatAmount(quote.estimatedTotal, quote.currency)}
        </dd>

        <dt className="text-slate-400">Saldo actual</dt>
        <dd className="text-right tabular-nums text-slate-300">
          {formatAmount(quote.userBalance, quote.currency)}
        </dd>

        <dt className="text-slate-400">Saldo después</dt>
        <dd
          className={cn(
            'text-right font-medium tabular-nums',
            canConfirm ? 'text-emerald-300' : 'text-slate-500',
          )}
        >
          {projectedBalance(quote)}
        </dd>

        {isSell && (
          <>
            <dt className="text-slate-400">Posición actual</dt>
            <dd className="text-right tabular-nums text-slate-200">
              {quote.userShares} {quote.ticker}
            </dd>
          </>
        )}
      </dl>

      {positionInfo && (
        <p className="mt-3 text-xs text-slate-500">{positionInfo}</p>
      )}

      {blockedReason && (
        <p
          role="alert"
          className="mt-4 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-sm text-amber-200"
        >
          {blockedReason}
        </p>
      )}

      <div className="mt-6 flex gap-2">
        <Button
          variant="primary"
          size="md"
          onClick={onConfirm}
          disabled={!canConfirm}
          isLoading={isSubmitting}
          className="flex-1"
        >
          {confirmLabel}
        </Button>
        <Button variant="ghost" size="md" onClick={onCancel} disabled={isSubmitting}>
          Cancelar
        </Button>
      </div>

      <p className="mt-3 text-xs text-slate-500">
        Quote válido al {formatLocalDateTimeWithSeconds(quote.quotedAt)}. El precio de
        ejecución puede variar ligeramente según el mercado.
      </p>
    </Card>
  );
}
