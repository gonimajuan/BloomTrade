import { forwardRef, type InputHTMLAttributes } from 'react';
import { Link } from 'react-router-dom';

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>;

/**
 * Checkbox de aceptación de términos con link a /terms (spec HU-F01 §12.1).
 * Forwarded ref para integrarse con react-hook-form.
 *
 * <p>Revamp UI Lote C: glass dark theme con accent violet en check + link violet.
 */
export const TermsCheckbox = forwardRef<HTMLInputElement, Props>(function TermsCheckbox(
  { className, ...rest },
  ref,
) {
  return (
    <label className="flex items-start gap-2.5 text-sm text-slate-300">
      <input
        ref={ref}
        type="checkbox"
        className={`mt-0.5 h-4 w-4 cursor-pointer rounded border-slate-600 bg-slate-900/60 text-violet-500 accent-violet-500 focus:ring-2 focus:ring-violet-400/50 focus:ring-offset-0 ${
          className ?? ''
        }`}
        {...rest}
      />
      <span>
        Acepto los{' '}
        <Link
          to="/terms"
          className="font-medium text-violet-300 underline-offset-4 transition-colors hover:text-violet-200 hover:underline"
        >
          términos y condiciones
        </Link>
      </span>
    </label>
  );
});
