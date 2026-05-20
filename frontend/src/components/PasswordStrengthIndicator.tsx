interface Props {
  password: string;
}

const LEVELS: { label: string; bar: string }[] = [
  { label: 'Muy débil', bar: 'bg-red-500' },
  { label: 'Débil', bar: 'bg-red-400' },
  { label: 'Media', bar: 'bg-yellow-400' },
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
 */
export function PasswordStrengthIndicator({ password }: Props) {
  const s = score(password);
  const lvl = LEVELS[s];

  return (
    <div className="mt-1" aria-live="polite">
      <div className="flex h-1 w-full gap-1">
        {Array.from({ length: 5 }).map((_, i) => (
          <span
            key={i}
            className={`flex-1 rounded ${i < s ? lvl.bar : 'bg-slate-700'}`}
          />
        ))}
      </div>
      {password.length > 0 && (
        <p className="mt-1 text-xs text-slate-400">Fortaleza: {lvl.label}</p>
      )}
    </div>
  );
}
