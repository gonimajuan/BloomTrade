import { type ReactNode, useEffect, useRef, useState } from 'react';
import { ChevronDown } from 'lucide-react';
import { AnimatePresence, motion } from 'framer-motion';
import { cn } from '@/lib/cn';

interface UserDropdownProps {
  trigger: ReactNode;
  children: ReactNode;
  align?: 'left' | 'right';
}

/**
 * Dropdown manual con click-outside + Escape. Sin Radix.
 * Suficiente para el user menu de AppHeader (single-user MVP).
 */
export function UserDropdown({ trigger, children, align = 'right' }: UserDropdownProps) {
  const [isOpen, setIsOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setIsOpen(false);
    };
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [isOpen]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setIsOpen((v) => !v)}
        className={cn(
          'inline-flex items-center gap-2 rounded-xl border border-white/10 bg-slate-900/60 px-3 py-1.5 text-sm text-slate-200 backdrop-blur-sm transition-colors hover:bg-slate-800/70',
          isOpen && 'border-white/20 bg-slate-800/70',
        )}
        aria-haspopup="menu"
        aria-expanded={isOpen}
      >
        {trigger}
        <ChevronDown
          className={cn('h-4 w-4 transition-transform', isOpen && 'rotate-180')}
          aria-hidden
        />
      </button>
      <AnimatePresence>
        {isOpen && (
          <motion.div
            initial={{ opacity: 0, scale: 0.95, y: -4 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.95, y: -4 }}
            transition={{ duration: 0.15 }}
            role="menu"
            className={cn(
              'absolute z-50 mt-2 min-w-[200px] overflow-hidden rounded-2xl border border-white/15 bg-slate-900/90 p-1 shadow-glass-lg backdrop-blur-xl',
              align === 'right' ? 'right-0' : 'left-0',
            )}
          >
            {children}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

interface UserDropdownItemProps {
  onClick?: () => void;
  children: ReactNode;
  variant?: 'default' | 'destructive';
}

export function UserDropdownItem({
  onClick,
  children,
  variant = 'default',
}: UserDropdownItemProps) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onClick}
      className={cn(
        'flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm transition-colors',
        variant === 'default'
          ? 'text-slate-200 hover:bg-white/5 hover:text-white'
          : 'text-rose-300 hover:bg-rose-500/10 hover:text-rose-200',
      )}
    >
      {children}
    </button>
  );
}
