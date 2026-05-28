interface Props {
  password: string;
}

const LEVELS: { label: string; bar: string }[] = [
  { label: 'Muy débil', bar: 'bg-rose-500' },
  { label: 'Débil', bar: 'bg-rose-400' },
  { label: 'Media', bar: 'bg-amber-400' },
  { label: 'Buena', bar: 'bg-emerald-400' },
  { label: 'Fuerte', bar: 'bg-emerald-500' },
  { label: 'Muy fuerte', bar: 'bg-emerald-600' },
];

function score(password: string): number {
  let s = 0;
  if (password.length >= 10) s += 1;
  if (/[A-Z]/.test(password)) s += 1;
  if (/[a-z]/.test(password)) s += 1;
  if (/\d/.test(password)) s += 1;
  if (password.length >= 14) s += 1;
  return Math.min(s, 5);
}

/**
 * Indicador visual de fortaleza (débil/media/fuerte) — spec HU-F01 §12.1. Solo informativo;
 * la política dura la enforza el backend con BCrypt y la validación zod del cliente.
 *
 * <p>Revamp UI Lote C: paleta rose/amber/emerald + track slate-800/60 para combinar con glass.
 */
export function PasswordStrengthIndicator({ password }: Props) {
  const s = score(password);
  const lvl = LEVELS[s];

  return (
    <div className="mt-2" aria-live="polite">
      <div className="flex h-1 w-full gap-1">
        {Array.from({ length: 5 }).map((_, i) => (
          <span
            key={i}
            className={`flex-1 rounded-full transition-colors ${i < s ? lvl.bar : 'bg-slate-800/60'}`}
          />
        ))}
      </div>
      {password.length > 0 && (
        <p className="mt-1.5 text-xs text-slate-400">
          Fortaleza: <span className="text-slate-200">{lvl.label}</span>
        </p>
      )}
    </div>
  );
}
