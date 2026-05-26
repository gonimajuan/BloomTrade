import { useEffect, useState } from 'react';
import { useMFAResend } from '../hooks/useMFAResend';
import type { ParsedError } from '@/lib/errorParser';
import type { MfaResendResponse } from '@/types/api';

interface ResendButtonProps {
  tempSessionId: string;
  /** Notifica al padre cuando un resend exitoso debe reiniciar el temporizador del OTP. */
  onResendSuccess?: (response: MfaResendResponse) => void;
  /**
   * Notifica al padre cuando el resend falla — incluido el cooldown 429. El padre puede
   * renderizar un banner contextual ("Esperá Ns…", "Sesión expirada…") en su propia área de
   * errores. El botón sigue manejando internamente el estado visual del cooldown/max-resends.
   */
  onResendError?: (error: ParsedError) => void;
}

const DEFAULT_COOLDOWN_SECONDS = 30;

/**
 * Botón "Reenviar código" con cooldown visual y conteo de reenvíos restantes (spec HU-F02 §12.1
 * — MFAVerifyPage / Lote H T7.5).
 *
 * <p>Estados:
 * <ul>
 *   <li><strong>idle</strong> — botón habilitado, listo para reenviar.</li>
 *   <li><strong>cooldown(N)</strong> — botón deshabilitado, muestra "Reenviar en Ns" y baja a 0.</li>
 *   <li><strong>maxed</strong> — botón deshabilitado permanentemente con mensaje informativo.</li>
 * </ul>
 *
 * <p>El cooldown viene del header {@code Retry-After} cuando el backend devuelve 429
 * {@code RESEND_COOLDOWN_ACTIVE}, o de la constante por defecto cuando el resend fue exitoso
 * (el cooldown del servidor es 30s — spec §9.3).
 */
export function ResendButton({
  tempSessionId,
  onResendSuccess,
  onResendError,
}: ResendButtonProps) {
  const mutation = useMFAResend();
  const [cooldown, setCooldown] = useState(0);
  const [maxed, setMaxed] = useState(false);

  useEffect(() => {
    if (cooldown <= 0) return;
    const id = window.setInterval(
      () => setCooldown((c) => Math.max(0, c - 1)),
      1000,
    );
    return () => window.clearInterval(id);
  }, [cooldown]);

  useEffect(() => {
    if (!mutation.isSuccess || !mutation.data) return;
    setCooldown(DEFAULT_COOLDOWN_SECONDS);
    if (mutation.data.resendsRemaining === 0) {
      setMaxed(true);
    }
    onResendSuccess?.(mutation.data);
  }, [mutation.isSuccess, mutation.data, onResendSuccess]);

  useEffect(() => {
    if (!mutation.error) return;
    if (mutation.error.code === 'RESEND_COOLDOWN_ACTIVE') {
      setCooldown(mutation.error.retryAfter ?? DEFAULT_COOLDOWN_SECONDS);
    } else if (mutation.error.code === 'MAX_RESENDS_EXCEEDED') {
      setMaxed(true);
    }
    onResendError?.(mutation.error);
  }, [mutation.error, onResendError]);

  if (maxed) {
    return (
      <p className="text-sm text-amber-300">
        Alcanzaste el máximo de reenvíos. Volvé al login para empezar de nuevo.
      </p>
    );
  }

  const disabled = cooldown > 0 || mutation.isPending;
  const label = cooldown > 0
    ? `Reenviar en ${cooldown}s`
    : mutation.isPending
    ? 'Reenviando…'
    : 'Reenviar código';

  return (
    <button
      type="button"
      onClick={() => mutation.mutate({ tempSessionId })}
      disabled={disabled}
      className="text-sm font-medium text-blue-400 hover:text-blue-300 disabled:cursor-not-allowed disabled:text-slate-500"
    >
      {label}
    </button>
  );
}
