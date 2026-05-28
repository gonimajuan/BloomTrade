import { type HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

type BadgeVariant = 'neutral' | 'success' | 'error' | 'warning' | 'accent';

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
}

const variantClasses: Record<BadgeVariant, string> = {
  neutral: 'border-white/10 bg-slate-800/60 text-slate-300',
  success: 'border-emerald-500/30 bg-emerald-500/15 text-emerald-300',
  error: 'border-rose-500/30 bg-rose-500/15 text-rose-300',
  warning: 'border-amber-500/30 bg-amber-500/15 text-amber-300',
  accent: 'border-violet-500/40 bg-violet-500/20 text-violet-200',
};

export function Badge({ variant = 'neutral', className, ...props }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border px-2.5 py-0.5 text-xs font-medium',
        variantClasses[variant],
        className,
      )}
      {...props}
    />
  );
}
