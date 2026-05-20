import { forwardRef, type InputHTMLAttributes } from 'react';
import { Link } from 'react-router-dom';

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>;

/**
 * Checkbox de aceptación de términos con link a /terms (spec HU-F01 §12.1).
 * Forwarded ref para integrarse con react-hook-form.
 */
export const TermsCheckbox = forwardRef<HTMLInputElement, Props>(function TermsCheckbox(
  { className, ...rest },
  ref,
) {
  return (
    <label className="flex items-start gap-2 text-sm text-slate-300">
      <input
        ref={ref}
        type="checkbox"
        className={`mt-0.5 h-4 w-4 rounded border-slate-600 ${className ?? ''}`}
        {...rest}
      />
      <span>
        Acepto los{' '}
        <Link to="/terms" className="text-blue-400 underline hover:text-blue-300">
          términos y condiciones
        </Link>
      </span>
    </label>
  );
});
