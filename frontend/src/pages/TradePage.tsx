import { useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { toast } from 'sonner';
import { AppHeader } from '@/components/AppHeader';
import { OrderForm, type OrderFormValues } from '@/features/trading/components/OrderForm';
import { OrderQuotePanel } from '@/features/trading/components/OrderQuotePanel';
import { useQuote } from '@/features/trading/hooks/useQuote';
import { useSubmitOrder } from '@/features/trading/hooks/useSubmitOrder';
import { Card } from '@/components/ui/Card';
import type { OrderResponse, QuoteResponse } from '@/types/api';

/**
 * Página `/trade` (HU-F09 §12.1 + F10 §12.1). Revamp Lote E:
 * - Dark glass + framer entry stagger.
 * - Quote panel entra con AnimatePresence slide-in desde la derecha.
 * - Confirmación migrada de OrderConfirmationToast (componente borrado) a sonner global
 *   (consistente con cancel feedback de HU-F15).
 */
export function TradePage() {
  const quoteMutation = useQuote();
  const submitMutation = useSubmitOrder();
  const [quote, setQuote] = useState<QuoteResponse | null>(null);

  const handleQuote = (values: OrderFormValues) => {
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
          showOrderConfirmationToast(data, isIdempotent);
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
    <>
      <AppHeader />
      <main className="mx-auto max-w-6xl px-6 py-10">
        <motion.header
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, ease: 'easeOut' }}
          className="mb-8"
        >
          <h1 className="text-3xl font-semibold tracking-tight text-white">Operar</h1>
          <p className="mt-1.5 text-sm text-slate-400">
            Elige un ticker, una cantidad, y obten un quote antes de confirmar tu orden.
            La comisión se calcula sobre el precio actual y se descuenta solo al confirmar.
          </p>
        </motion.header>

        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <motion.div
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, ease: 'easeOut', delay: 0.05 }}
          >
            <OrderForm
              onSubmit={handleQuote}
              isQuoting={quoteMutation.isPending}
              quoteError={quoteMutation.error?.message ?? null}
            />
          </motion.div>

          <AnimatePresence mode="wait">
            {quote ? (
              <motion.div
                key="quote"
                initial={{ opacity: 0, x: 24 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: 24 }}
                transition={{ duration: 0.3, ease: 'easeOut' }}
              >
                <OrderQuotePanel
                  quote={quote}
                  onConfirm={handleConfirm}
                  onCancel={handleCancelQuote}
                  isSubmitting={submitMutation.isPending}
                  submitError={submitMutation.error ?? null}
                />
              </motion.div>
            ) : (
              <motion.div
                key="placeholder"
                initial={{ opacity: 0, y: 16 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.4, ease: 'easeOut', delay: 0.1 }}
              >
                <Card
                  variant="glass-outline"
                  className="border-dashed p-6 text-sm leading-relaxed text-slate-400"
                >
                  Llena el formulario y presiona{' '}
                  <strong className="text-slate-200">Obtener quote</strong> para ver el
                  precio, la comisión y el total que se te descontará.
                </Card>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </main>
    </>
  );
}

/**
 * Muestra el toast de confirmación post-ejecución vía sonner.
 * Reemplaza el componente OrderConfirmationToast (borrado en Lote E del revamp).
 */
function showOrderConfirmationToast(order: OrderResponse, isIdempotent: boolean) {
  const isSell = order.side === 'SELL';
  const isQueued = order.status === 'PENDING';
  const verb = isSell ? 'venta' : 'compra';
  const pastVerb = isSell ? 'Vendiste' : 'Compraste';
  const unitPrice = order.executionUnitPrice ?? order.quotedUnitPrice;
  const total = order.executionTotal ?? order.quotedTotal ?? '—';

  const headline = isIdempotent
    ? 'Tu orden ya estaba registrada'
    : isQueued
      ? `Orden de ${verb} en cola: ${order.quantity} ${order.ticker}`
      : `${pastVerb} ${order.quantity} ${order.ticker}`;

  const description = isQueued
    ? isSell
      ? `Mercado cerrado. Recibirás USD $${total} al ejecutarse al abrir.`
      : `Mercado cerrado. Se ejecutará al abrir. Saldo reservado: USD $${total}.`
    : isSell
      ? `Precio: USD $${unitPrice} · Recibiste USD $${total}`
      : `Precio unitario: USD $${unitPrice} · Total: USD $${total}`;

  if (isQueued) {
    toast.info(headline, { description, duration: 6000 });
  } else {
    toast.success(headline, { description, duration: 6000 });
  }
}
