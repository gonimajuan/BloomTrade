import type { QuoteResponse } from '@/types/api';

const fmt = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

function formatAmount(amount: string, currency: string): string {
  const n = Number(amount);
  return Number.isFinite(n) ? `${currency} $${fmt.format(n)}` : `${currency} ${amount}`;
}

function balanceAfter(quote: QuoteResponse): string {
  const before = Number(quote.userBalance);
  const total = Number(quote.estimatedTotal);
  if (!Number.isFinite(before) || !Number.isFinite(total)) {
    return formatAmount(quote.userBalance, quote.currency);
  }
  return `${quote.currency} $${fmt.format(before - total)}`;
}

interface Props {
  quote: QuoteResponse;
  onConfirm: () => void;
  onCancel: () => void;
  isSubmitting: boolean;
  /** Error de la última submisión (si la hubo), mostrado como banner inline. */
  submitError?: string | null;
}

/**
 * Panel que muestra el quote calculado (SPEC §12.1 paso 3-5) y habilita la confirmación.
 *
 * <p>El botón "Confirmar compra" se deshabilita cuando:
 * <ul>
 *   <li>{@code !quote.sufficientFunds} — backend ya marcó que el saldo no alcanza.</li>
 *   <li>{@code !quote.marketOpen} — backend reporta mercado cerrado (stub MVP siempre true).</li>
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
  const canConfirm = quote.sufficientFunds && quote.marketOpen && !isSubmitting;
  const blockedReason = !quote.sufficientFunds
    ? 'Tu saldo no alcanza para esta orden. Reduce la cantidad y pide otro quote.'
    : !quote.marketOpen
      ? 'El mercado está cerrado en este momento.'
      : null;

  return (
    <section
      aria-live="polite"
      className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
    >
      <header className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-slate-900">Resumen de la orden</h2>
        <span className="rounded-md bg-slate-100 px-2 py-0.5 text-xs uppercase tracking-wide text-slate-600">
          {quote.side === 'BUY' ? 'Compra' : 'Venta'} · Market
        </span>
      </header>

      {submitError && (
        <div
          role="alert"
          className="mt-4 rounded-md border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
        >
          {submitError}
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

        <dt className="border-t border-slate-200 pt-2 text-slate-700">Total a descontar</dt>
        <dd className="border-t border-slate-200 pt-2 text-right text-base font-semibold text-slate-900">
          {formatAmount(quote.estimatedTotal, quote.currency)}
        </dd>

        <dt className="text-slate-600">Saldo actual</dt>
        <dd className="text-right text-slate-900">
          {formatAmount(quote.userBalance, quote.currency)}
        </dd>

        <dt className="text-slate-600">Saldo después</dt>
        <dd
          className={`text-right font-medium ${
            quote.sufficientFunds ? 'text-emerald-700' : 'text-red-700'
          }`}
        >
          {balanceAfter(quote)}
        </dd>
      </dl>

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
          {isSubmitting ? 'Enviando orden…' : 'Confirmar compra'}
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
        Quote válido al {new Date(quote.quotedAt).toLocaleString()}. El precio de ejecución
        puede variar ligeramente según el mercado.
      </p>
    </section>
  );
}
