import { forwardRef, type InputHTMLAttributes } from 'react';

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>;

/**
 * Input de teléfono en formato E.164 (spec HU-F01 §6.1). Reutilizable. Forwarded ref para
 * que react-hook-form pueda registrarlo. La validación E.164 vive en el zod schema.
 */
export const PhoneInput = forwardRef<HTMLInputElement, Props>(function PhoneInput(props, ref) {
  return (
    <input
      ref={ref}
      type="tel"
      inputMode="tel"
      autoComplete="tel"
      placeholder="+573001234567"
      {...props}
    />
  );
});
