import { useState } from 'react';
import { AppHeader } from '@/components/AppHeader';
import { OrderForm, type OrderFormValues } from '@/features/trading/components/OrderForm';
import { OrderQuotePanel } from '@/features/trading/components/OrderQuotePanel';
import { OrderConfirmationToast } from '@/features/trading/components/OrderConfirmationToast';
import { useQuote } from '@/features/trading/hooks/useQuote';
import { useSubmitOrder } from '@/features/trading/hooks/useSubmitOrder';
import type { OrderResponse, QuoteResponse } from '@/types/api';

interface Confirmation {
  order: OrderResponse;
  isIdempotent: boolean;
}

/**
 * Página `/trade` (HU-F09 §12.1). Orquesta el ciclo IDLE → QUOTE_SHOWN → SUBMITTING → SUCCESS:
 * <ol>
 *   <li>El usuario llena {@link OrderForm} y pide quote.</li>
 *   <li>El backend devuelve {@link QuoteResponse}; se renderiza {@link OrderQuotePanel}.</li>
 *   <li>El usuario confirma; se llama POST /orders y aparece {@link OrderConfirmationToast}.</li>
 *   <li>Tras éxito el quote se limpia y el form vuelve a estar disponible para una nueva orden
 *       (decisión D-UI-1 del plan: no redirigir a /portfolio hasta HU-F16).</li>
 * </ol>
 */
export function TradePage() {
  const quoteMutation = useQuote();
  const submitMutation = useSubmitOrder();
  const [quote, setQuote] = useState<QuoteResponse | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);

  const handleQuote = (values: OrderFormValues) => {
    setConfirmation(null);
    submitMutation.reset();
    quoteMutation.mutate(
      { ticker: values.ticker, side: values.side, quantity: values.quantity },
      {
        onSuccess: (data) => setQuote(data),
        onError: () => setQuote(null),
      },
    );
  };

  const handleConfirm = () => {
    if (!quote) return;
    submitMutation.mutate(
      {
        ticker: quote.ticker,
        side: quote.side,
        type: 'MARKET',
        quantity: quote.quantity,
      },
      {
        onSuccess: ({ data, isIdempotent }) => {
          setConfirmation({ order: data, isIdempotent });
          setQuote(null);
          quoteMutation.reset();
        },
      },
    );
  };

  const handleCancelQuote = () => {
    setQuote(null);
    quoteMutation.reset();
    submitMutation.reset();
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <main className="mx-auto max-w-5xl px-6 py-10">
        <header className="mb-8">
          <h1 className="text-2xl font-semibold text-slate-900">Operar</h1>
          <p className="mt-1 text-sm text-slate-600">
            Elige un ticker, una cantidad y obtén un quote antes de confirmar tu orden.
            La comisión se calcula sobre el precio actual y se descuenta de tu saldo solo
            al confirmar.
          </p>
        </header>

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <OrderForm
            onSubmit={handleQuote}
            isQuoting={quoteMutation.isPending}
            quoteError={quoteMutation.error?.message ?? null}
          />

          {quote ? (
            <OrderQuotePanel
              quote={quote}
              onConfirm={handleConfirm}
              onCancel={handleCancelQuote}
              isSubmitting={submitMutation.isPending}
              submitError={submitMutation.error ?? null}
            />
          ) : (
            <aside className="rounded-lg border border-dashed border-slate-300 bg-white p-6 text-sm text-slate-500">
              Llena el formulario y presiona <strong>Obtener quote</strong> para ver el precio,
              la comisión y el total que se te descontará.
            </aside>
          )}
        </div>
      </main>

      {confirmation && (
        <OrderConfirmationToast
          order={confirmation.order}
          isIdempotent={confirmation.isIdempotent}
          onClose={() => setConfirmation(null)}
        />
      )}
    </div>
  );
}
