import { forwardRef, type InputHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  isInvalid?: boolean;
}

/**
 * Input glass — focus violet ring + bg semi-translúcido. ForwardRef habilitado
 * para react-hook-form (`{...register('campo')}`).
 */
export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ isInvalid, className, ...props }, ref) => (
    <input
      ref={ref}
      aria-invalid={isInvalid || undefined}
      className={cn(
        'block w-full rounded-xl border bg-slate-900/60 px-4 py-2.5 text-sm text-slate-100 backdrop-blur-sm transition-colors',
        'placeholder:text-slate-500',
        'focus:border-violet-400/50 focus:outline-none focus:ring-2 focus:ring-violet-400/50',
        'disabled:cursor-not-allowed disabled:opacity-50',
        isInvalid
          ? 'border-rose-500/50 focus:border-rose-400/50 focus:ring-rose-400/50'
          : 'border-white/10 hover:border-white/20',
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = 'Input';
