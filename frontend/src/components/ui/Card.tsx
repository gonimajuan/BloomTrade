import { type HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

type CardVariant = 'glass' | 'glass-elevated' | 'glass-outline';

interface CardProps extends HTMLAttributes<HTMLDivElement> {
  variant?: CardVariant;
}

const variantClasses: Record<CardVariant, string> = {
  glass: 'border border-white/10 bg-slate-900/40 shadow-glass backdrop-blur-xl',
  'glass-elevated':
    'border border-white/15 bg-slate-900/60 shadow-glass-lg backdrop-blur-xl',
  'glass-outline':
    'border border-white/15 bg-transparent backdrop-blur-sm',
};

/**
 * Card glass — primitive base del revamp UI. Variante `glass` (default) es la
 * card típica del producto; `glass-elevated` es para modales / heroes con más
 * elevación visual; `glass-outline` es para containers ligeros sin fondo.
 *
 * <p>Requiere que exista atmósfera (orbes/gradiente) detrás para que el
 * backdrop-blur tenga algo que difuminar — ver {@link GlassBackground}.
 */
export function Card({ variant = 'glass', className, ...props }: CardProps) {
  return (
    <div
      className={cn('rounded-2xl', variantClasses[variant], className)}
      {...props}
    />
  );
}
