import { forwardRef, type InputHTMLAttributes } from 'react';
import { Input } from '@/components/ui/Input';

interface Props extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  isInvalid?: boolean;
}

/**
 * Input de teléfono en formato E.164 (spec HU-F01 §6.1). Reutilizable.
 * Pasa por el primitive {@link Input} (glass dark theme + violet focus).
 * Forwarded ref para que react-hook-form pueda registrarlo. La validación
 * E.164 vive en el zod schema.
 */
export const PhoneInput = forwardRef<HTMLInputElement, Props>(function PhoneInput(
  props,
  ref,
) {
  return (
    <Input
      ref={ref}
      type="tel"
      inputMode="tel"
      autoComplete="tel"
      placeholder="+573001234567"
      {...props}
    />
  );
});
