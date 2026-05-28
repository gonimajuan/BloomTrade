import { useMemo } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ALL_TICKERS } from '@/constants/tickers';
import { usePortfolioPositions } from '@/features/portfolio/hooks/usePortfolioPositions';
import { humanFor } from '@/lib/messages.es';
import { TickerDropdown } from './TickerDropdown';
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { cn } from '@/lib/cn';
import type { OrderSide } from '@/types/api';

const orderFormSchema = z.object({
  ticker: z
    .string()
    .min(1, { message: 'INVALID_TICKER' })
    .refine((t) => ALL_TICKERS.includes(t), { message: 'INVALID_TICKER' }),
  side: z.enum(['BUY', 'SELL'], {
    errorMap: () => ({ message: 'INVALID_SIDE' }),
  }),
  quantity: z.coerce
    .number({ invalid_type_error: 'INVALID_QUANTITY' })
    .int({ message: 'INVALID_QUANTITY' })
    .min(1, { message: 'INVALID_QUANTITY' })
    .max(10000, { message: 'INVALID_QUANTITY' }),
});

export type OrderFormValues = z.infer<typeof orderFormSchema>;

const LABEL = 'mb-1.5 block text-sm font-medium text-slate-300';
const ERR = 'mt-1.5 text-xs text-rose-300';
const SELECT_CLASSES =
  'block w-full rounded-xl border border-white/10 bg-slate-900/60 px-4 py-2.5 text-sm text-slate-100 backdrop-blur-sm transition-colors hover:border-white/20 focus:border-violet-400/50 focus:outline-none focus:ring-2 focus:ring-violet-400/50 disabled:cursor-not-allowed disabled:opacity-50';

interface Props {
  onSubmit: (values: OrderFormValues) => void;
  isQuoting: boolean;
  quoteError?: string | null;
}

/**
 * Form de captura de orden Market (SPEC F09 + F10). Revamp Lote E:
 * - Card glass-elevated wrapper.
 * - Side toggle como segmented pill control (BUY emerald glow / SELL rose glow).
 * - Inputs vía primitives.
 * - SELL filter de tickers preservado de HU-F18.
 */
export function OrderForm({ onSubmit, isQuoting, quoteError }: Props) {
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isValid },
  } = useForm<OrderFormValues>({
    resolver: zodResolver(orderFormSchema),
    mode: 'onChange',
    defaultValues: { ticker: '', side: 'BUY' as OrderSide, quantity: 1 },
  });

  const currentSide = watch('side');

  const positionsQuery = usePortfolioPositions();
  const ownedTickers = useMemo<ReadonlySet<string> | undefined>(() => {
    if (currentSide !== 'SELL') return undefined;
    if (!positionsQuery.data) return new Set<string>();
    return new Set(positionsQuery.data.positions.map((p) => p.ticker));
  }, [currentSide, positionsQuery.data]);
  const noPositionsForSell =
    currentSide === 'SELL' &&
    ownedTickers !== undefined &&
    ownedTickers.size === 0 &&
    !positionsQuery.isLoading;

  const submitLabel = isQuoting
    ? 'Cotizando…'
    : currentSide === 'SELL'
      ? 'Obtener quote de venta'
      : 'Obtener quote de compra';

  return (
    <Card variant="glass-elevated" className="p-6">
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-5">
        <h2 className="text-lg font-semibold text-white">Nueva orden</h2>

        {quoteError && (
          <div
            role="alert"
            className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
          >
            {quoteError}
          </div>
        )}

        <div>
          <span className={LABEL}>Tipo de operación</span>
          <div className="grid grid-cols-2 gap-2">
            <label
              className={cn(
                'flex cursor-pointer items-center justify-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-medium transition-all',
                currentSide === 'BUY'
                  ? 'border-emerald-500/50 bg-emerald-500/15 text-emerald-200 shadow-glow-emerald-sm'
                  : 'border-white/10 bg-slate-900/40 text-slate-300 hover:border-white/20',
                isQuoting && 'cursor-not-allowed opacity-50',
              )}
            >
              <input
                type="radio"
                value="BUY"
                className="sr-only"
                {...register('side')}
                disabled={isQuoting}
              />
              Comprar
            </label>
            <label
              className={cn(
                'flex cursor-pointer items-center justify-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-medium transition-all',
                currentSide === 'SELL'
                  ? 'border-rose-500/50 bg-rose-500/15 text-rose-200 shadow-glow-rose-sm'
                  : 'border-white/10 bg-slate-900/40 text-slate-300 hover:border-white/20',
                isQuoting && 'cursor-not-allowed opacity-50',
              )}
            >
              <input
                type="radio"
                value="SELL"
                className="sr-only"
                {...register('side')}
                disabled={isQuoting}
              />
              Vender
            </label>
          </div>
          {errors.side && (
            <p className={ERR} role="alert">
              {humanFor(errors.side.message ?? '')}
            </p>
          )}
        </div>

        <div>
          <label className={LABEL} htmlFor="ticker">
            Ticker
          </label>
          <TickerDropdown
            id="ticker"
            className={SELECT_CLASSES}
            disabled={isQuoting || noPositionsForSell}
            ownedTickers={ownedTickers}
            {...register('ticker')}
          />
          {currentSide === 'SELL' && !noPositionsForSell && (
            <p className="mt-1.5 text-xs text-slate-500">
              Solo se muestran tickers que tenés en tu portafolio.
            </p>
          )}
          {noPositionsForSell && (
            <p className="mt-1.5 text-xs text-amber-300" role="alert">
              No tenés posiciones para vender. Realizá una compra primero.
            </p>
          )}
          {errors.ticker && (
            <p className={ERR} role="alert">
              {humanFor(errors.ticker.message ?? '')}
            </p>
          )}
        </div>

        <div>
          <label className={LABEL} htmlFor="quantity">
            Cantidad
          </label>
          <Input
            id="quantity"
            type="number"
            inputMode="numeric"
            min={1}
            max={10000}
            step={1}
            disabled={isQuoting}
            isInvalid={!!errors.quantity}
            {...register('quantity')}
          />
          {errors.quantity && (
            <p className={ERR} role="alert">
              {humanFor(errors.quantity.message ?? '')}
            </p>
          )}
        </div>

        <Button
          type="submit"
          disabled={!isValid}
          isLoading={isQuoting}
          className="w-full"
        >
          {submitLabel}
        </Button>
      </form>
    </Card>
  );
}
