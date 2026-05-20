import { useEffect, useState } from 'react';

interface CountdownProps {
  /** Momento de expiración absoluto. Si el valor cambia, el contador se reinicia. */
  expiresAt: Date;
  /** Callback opcional invocado una sola vez cuando el contador llega a 00:00. */
  onExpire?: () => void;
}

/**
 * Temporizador visual MM:SS reutilizable (spec HU-F02 §12.1 — MFAVerifyPage). Se actualiza cada
 * segundo vía {@code setInterval} y limpia el timer al desmontar. {@code onExpire} dispara una
 * sola vez aunque el componente siga vivo en pantalla.
 */
export function Countdown({ expiresAt, onExpire }: CountdownProps) {
  const [remaining, setRemaining] = useState(() => secondsUntil(expiresAt));

  useEffect(() => {
    let expired = false;
    const tick = () => {
      const next = secondsUntil(expiresAt);
      setRemaining(next);
      if (next === 0 && !expired) {
        expired = true;
        onExpire?.();
      }
    };
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [expiresAt, onExpire]);

  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;
  return (
    <span aria-live="polite" className="tabular-nums">
      {pad(minutes)}:{pad(seconds)}
    </span>
  );
}

function secondsUntil(date: Date): number {
  return Math.max(0, Math.floor((date.getTime() - Date.now()) / 1000));
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}
