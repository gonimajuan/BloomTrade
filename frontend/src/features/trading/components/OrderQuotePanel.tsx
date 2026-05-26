import { formatLocalDateTimeWithSeconds } from '@/lib/dateFormat';
import type { ParsedError } from '@/lib/errorParser';
import type { QuoteResponse } from '@/types/api';

const fmt = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

function formatAmount(amount: string, currency: string): string {
  const n = Number(amount);
  return Number.isFinite(n) ? `${currency} $${fmt.format(n)}` : `${currency} ${amount}`;
}

/**
 * Saldo proyectado tras la operación. Side-aware:
 * - BUY: balance − estimatedTotal (descuento).
 * - SELL: balance + estimatedTotal (crédito del producto neto).
 *
 * Se calcula client-side aceptando un riesgo menor de precision drift (las cifras son
 * informativas; el saldo real autoritativo lo entrega el backend post-ejecución).
 */
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
  /**
   * Error de la última submisión (si la hubo), mostrado como banner inline. P1-1 audit:
   * pasamos el {@link ParsedError} completo para incluir el {@code code} (útil para soporte
   * y debugging) además del mensaje humano.
   */
  submitError?: ParsedError | null;
}

/**
 * Panel que muestra el quote calculado (SPEC F09 §12.1 paso 3-5 + F10 §12.1).
 *
 * <p>HU-F10 side-aware: para SELL se reinterpreta el wording ("Producto neto a recibir"
 * en lugar de "Total a descontar"), el saldo proyectado SUMA en lugar de RESTAR, y se
 * agrega una línea con la posición restante o aviso de liquidación total.
 *
 * <p>El botón "Confirmar" se deshabilita cuando:
 * <ul>
 *   <li>BUY: {@code !quote.sufficientFunds} o {@code !quote.marketOpen}.</li>
 *   <li>SELL: {@code !quote.sufficientShares} o {@code !quote.marketOpen}.</li>
 *   <li>{@code isSubmitting} — orden en vuelo; evita doble-submit.</li>
 * </ul>
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
        ? `No tienes posición en ${quote.ticker}. Compra primero para poder vender.`
        : `Solo tienes ${quote.userShares} ${quote.ticker} disponibles. Reduce la cantidad.`;
    }
    if (!isSell && !quote.sufficientFunds) {
      return 'Tu saldo no alcanza para esta orden. Reduce la cantidad y pide otro quote.';
    }
    if (!quote.marketOpen) {
      return 'El mercado está cerrado en este momento.';
    }
    return null;
  })();

  // SELL-only — info de posición resultante.
  const positionInfo = (() => {
    if (!isSell || !quote.sufficientShares) return null;
    if (quote.userShares === quote.quantity) {
      return `Esta venta liquidará tu posición completa en ${quote.ticker}.`;
    }
    return `Posición restante tras la venta: ${quote.userShares - quote.quantity} ${quote.ticker}.`;
  })();

  return (
    <section
      aria-live="polite"
      className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
    >
      <header className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-slate-900">Resumen de la orden</h2>
        <span className="rounded-md bg-slate-100 px-2 py-0.5 text-xs uppercase tracking-wide text-slate-600">
          {isSell ? 'Venta' : 'Compra'} · Market
        </span>
      </header>

      {submitError && (
        <div
          role="alert"
          className="mt-4 rounded-md border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
        >
          <p>{submitError.message}</p>
          <p className="mt-1 text-xs italic text-red-600">
            Código: {submitError.code}
            {submitError.traceId && ` · traceId: ${submitError.traceId}`}
          </p>
        </div>
      )}

      <dl className="mt-4 grid grid-cols-2 gap-y-2 text-sm">
        <dt className="text-slate-600">Ticker</dt>
        <dd className="text-right font-medium text-slate-900">{quote.ticker}</dd>

        <dt className="text-slate-600">Cantidad</dt>
        <dd className="text-right font-medium text-slate-900">
          {quote.quantity.toLocaleString('en-US')}
        </dd>

        <dt className="text-slate-600">Precio unitario estimado</dt>
        <dd className="text-right font-medium text-slate-900">
          {formatAmount(quote.estimatedUnitPrice, quote.currency)}
        </dd>

        <dt className="text-slate-600">Subtotal</dt>
        <dd className="text-right font-medium text-slate-900">
          {formatAmount(quote.estimatedSubtotal, quote.currency)}
        </dd>

        <dt className="text-slate-600">Comisión (2%)</dt>
        <dd className="text-right font-medium text-slate-900">
          {formatAmount(quote.commission, quote.currency)}
        </dd>

        <dt className="border-t border-slate-200 pt-2 text-slate-700">{totalLabel}</dt>
        <dd
          className={`border-t border-slate-200 pt-2 text-right text-base font-semibold ${
            isSell ? 'text-emerald-700' : 'text-slate-900'
          }`}
        >
          {formatAmount(quote.estimatedTotal, quote.currency)}
        </dd>

        <dt className="text-slate-600">Saldo actual</dt>
        <dd className="text-right text-slate-900">
          {formatAmount(quote.userBalance, quote.currency)}
        </dd>

        <dt className="text-slate-600">Saldo después</dt>
        <dd
          className={`text-right font-medium ${
            canConfirm ? 'text-emerald-700' : 'text-slate-500'
          }`}
        >
          {projectedBalance(quote)}
        </dd>

        {isSell && (
          <>
            <dt className="text-slate-600">Posición actual</dt>
            <dd className="text-right text-slate-900">
              {quote.userShares} {quote.ticker}
            </dd>
          </>
        )}
      </dl>

      {positionInfo && (
        <p className="mt-3 text-xs text-slate-600">{positionInfo}</p>
      )}

      {blockedReason && (
        <p
          role="alert"
          className="mt-4 rounded-md border border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-800"
        >
          {blockedReason}
        </p>
      )}

      <div className="mt-6 flex gap-3">
        <button
          type="button"
          onClick={onConfirm}
          disabled={!canConfirm}
          className="flex-1 rounded-md bg-emerald-600 px-4 py-2 font-semibold text-white shadow hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {confirmLabel}
        </button>
        <button
          type="button"
          onClick={onCancel}
          disabled={isSubmitting}
          className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-40"
        >
          Cancelar
        </button>
      </div>

      <p className="mt-3 text-xs text-slate-500">
        Quote válido al {formatLocalDateTimeWithSeconds(quote.quotedAt)}. El precio de
        ejecución puede variar ligeramente según el mercado.
      </p>
    </section>
  );
}
