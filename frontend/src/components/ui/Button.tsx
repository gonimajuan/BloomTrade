import { type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';

type ButtonVariant = 'primary' | 'ghost' | 'destructive' | 'subtle';
type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  isLoading?: boolean;
  leadingIcon?: ReactNode;
}

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    'border border-violet-500/50 bg-violet-600 text-white shadow-glow-violet-sm hover:bg-violet-500 hover:shadow-glow-violet',
  ghost:
    'border border-transparent bg-transparent text-slate-200 hover:border-white/10 hover:bg-white/5',
  destructive:
    'border border-rose-500/50 bg-rose-600/90 text-white shadow-glow-rose-sm hover:bg-rose-500',
  subtle:
    'border border-white/10 bg-slate-800/60 text-slate-100 backdrop-blur-sm hover:bg-slate-700/70',
};

const sizeClasses: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-base',
};

export function Button({
  variant = 'primary',
  size = 'md',
  isLoading = false,
  leadingIcon,
  disabled,
  className,
  children,
  type = 'button',
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || isLoading}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-xl font-medium transition-all duration-200',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-400 focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950',
        'disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:shadow-none',
        variantClasses[variant],
        sizeClasses[size],
        className,
      )}
      {...props}
    >
      {isLoading && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}
      {!isLoading && leadingIcon}
      {children}
    </button>
  );
}
