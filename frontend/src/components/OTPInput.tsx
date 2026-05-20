import { useRef, type ClipboardEvent, type KeyboardEvent } from 'react';

interface OTPInputProps {
  value: string;
  onChange: (next: string) => void;
  disabled?: boolean;
  autoFocus?: boolean;
  invalid?: boolean;
}

const SLOTS = 6;
const SLOT_INDICES = Array.from({ length: SLOTS }, (_, i) => i);

/**
 * Input de 6 dígitos con auto-focus al siguiente al tipear y soporte de paste de 6 dígitos
 * (spec HU-F02 §12.1 / Lote H T7.4). El valor canónico es {@code value: string} de 0-6 dígitos;
 * cada slot renderiza un caracter (o cadena vacía).
 */
export function OTPInput({
  value,
  onChange,
  disabled,
  autoFocus,
  invalid,
}: OTPInputProps) {
  const refs = useRef<Array<HTMLInputElement | null>>([]);
  const digits = padToSlots(value);

  const writeDigit = (index: number, char: string) => {
    if (char && !/^\d$/.test(char)) {
      return;
    }
    const next = [...digits];
    next[index] = char;
    const collapsed = next.join('').slice(0, SLOTS);
    onChange(collapsed);
    if (char && index < SLOTS - 1) {
      refs.current[index + 1]?.focus();
      refs.current[index + 1]?.select?.();
    }
  };

  const handleKeyDown = (
    index: number,
    event: KeyboardEvent<HTMLInputElement>,
  ) => {
    if (event.key === 'Backspace' && !digits[index] && index > 0) {
      event.preventDefault();
      refs.current[index - 1]?.focus();
    } else if (event.key === 'ArrowLeft' && index > 0) {
      event.preventDefault();
      refs.current[index - 1]?.focus();
    } else if (event.key === 'ArrowRight' && index < SLOTS - 1) {
      event.preventDefault();
      refs.current[index + 1]?.focus();
    }
  };

  const handlePaste = (event: ClipboardEvent<HTMLInputElement>) => {
    const pasted = event.clipboardData.getData('text').replace(/\D/g, '');
    if (pasted.length === 0) return;
    event.preventDefault();
    const trimmed = pasted.slice(0, SLOTS);
    onChange(trimmed);
    const focusIdx = Math.min(SLOTS - 1, trimmed.length - 1);
    refs.current[focusIdx]?.focus();
  };

  const slotClass = invalid
    ? 'border-red-500 focus:border-red-400'
    : 'border-slate-700 focus:border-blue-500';

  return (
    <div className="flex justify-center gap-2" role="group" aria-label="Código OTP de 6 dígitos">
      {SLOT_INDICES.map((index) => (
        <input
          key={index}
          ref={(el) => {
            refs.current[index] = el;
          }}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={1}
          value={digits[index] ?? ''}
          aria-label={`Dígito ${index + 1}`}
          disabled={disabled}
          autoFocus={autoFocus && index === 0}
          onChange={(e) => writeDigit(index, e.target.value.slice(-1))}
          onKeyDown={(e) => handleKeyDown(index, e)}
          onPaste={handlePaste}
          className={`h-12 w-10 rounded-md border bg-slate-900 text-center text-xl font-semibold text-slate-100 focus:outline-none disabled:opacity-50 ${slotClass}`}
        />
      ))}
    </div>
  );
}

function padToSlots(value: string): string[] {
  const digits = value.split('').slice(0, SLOTS);
  while (digits.length < SLOTS) {
    digits.push('');
  }
  return digits;
}
