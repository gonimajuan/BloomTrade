import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ALL_TICKERS } from '@/constants/tickers';
import { humanFor } from '@/lib/messages.es';
import { TickerDropdown } from './TickerDropdown';
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

const INPUT =
  'w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-slate-900 focus:border-blue-500 focus:outline-none disabled:opacity-50';
const LABEL = 'block text-sm font-medium text-slate-700';
const ERR = 'mt-1 text-xs text-red-600';

interface Props {
  onSubmit: (values: OrderFormValues) => void;
  /** True mientras la mutación `useQuote` está pendiente. */
  isQuoting: boolean;
  /** Error de la última cotización (si la hubo), mostrado como banner inline. */
  quoteError?: string | null;
}

/**
 * Form de captura del input para una orden Market (SPEC F09 §12.1 + F10 §12.1):
 * ticker (25 permitidos) + side (BUY o SELL, ambos habilitados desde HU-F10) +
 * cantidad (1..10000). Al hacer submit invoca {@code onSubmit(values)}; la mutación
 * de quote vive en el padre para que el panel resultante pueda mostrarse a la par.
 *
 * <p>HU-F10 D10: el {@link TickerDropdown} NO filtra por posiciones del usuario en MVP.
 * Si elige SELL sobre un ticker sin posición, el backend responde
 * {@code SHORT_SELLING_NOT_ALLOWED}; el frontend lo renderiza claramente.
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
  const submitLabel = isQuoting
    ? 'Cotizando…'
    : currentSide === 'SELL'
      ? 'Obtener quote de venta'
      : 'Obtener quote de compra';

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      className="space-y-4 rounded-lg border border-slate-200 bg-white p-6 shadow-sm"
    >
      <h2 className="text-lg font-semibold text-slate-900">Nueva orden</h2>

      {quoteError && (
        <div
          role="alert"
          className="rounded-md border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
        >
          {quoteError}
        </div>
      )}

      <div>
        <label className={LABEL} htmlFor="ticker">
          Ticker
        </label>
        <TickerDropdown
          id="ticker"
          className={INPUT}
          disabled={isQuoting}
          {...register('ticker')}
        />
        {currentSide === 'SELL' && (
          <p className="mt-1 text-xs text-slate-500">
            Selecciona un ticker que tengas en tu portafolio. El sistema validará al confirmar.
          </p>
        )}
        {errors.ticker && (
          <p className={ERR} role="alert">
            {humanFor(errors.ticker.message ?? '')}
          </p>
        )}
      </div>

      <div>
        <span className={LABEL}>Tipo de operación</span>
        <div className="mt-2 flex gap-4">
          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="radio"
              value="BUY"
              {...register('side')}
              disabled={isQuoting}
            />
            Comprar
          </label>
          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="radio"
              value="SELL"
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
        <label className={LABEL} htmlFor="quantity">
          Cantidad
        </label>
        <input
          id="quantity"
          type="number"
          inputMode="numeric"
          min={1}
          max={10000}
          step={1}
          className={INPUT}
          disabled={isQuoting}
          {...register('quantity')}
        />
        {errors.quantity && (
          <p className={ERR} role="alert">
            {humanFor(errors.quantity.message ?? '')}
          </p>
        )}
      </div>

      <button
        type="submit"
        disabled={!isValid || isQuoting}
        className="w-full rounded-md bg-blue-600 px-4 py-2 font-semibold text-white shadow hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {submitLabel}
      </button>
    </form>
  );
}
