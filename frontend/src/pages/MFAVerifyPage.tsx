import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { OTPInput } from '@/components/OTPInput';
import { Countdown } from '@/components/Countdown';
import { ResendButton } from '@/features/auth/components/ResendButton';
import { useMFAVerify } from '@/features/auth/hooks/useMFAVerify';
import { useAuth } from '@/features/auth/context/AuthContext';
import { mfaSchema } from '@/features/auth/schemas/mfa';
import type { ParsedError } from '@/lib/errorParser';
import { humanFor } from '@/lib/messages.es';
import type { MfaResendResponse } from '@/types/api';

interface MfaNavState {
  tempSessionId?: string;
  email?: string;
  expiresAt?: string;
}

/**
 * Página /mfa-verify (spec HU-F02 §12.1 — paso 2 del flujo). Requiere {@code location.state} con
 * el {@code tempSessionId}, {@code email} y {@code expiresAt} que dejó {@link LoginForm}. Sin esos
 * datos redirige a /login.
 *
 * <p>Al éxito del verify guarda la sesión en el AuthContext y navega a /dashboard. Si el OTP
 * expira en pantalla, deshabilita el botón Verificar y resalta el botón de reenvío.
 */
export function MFAVerifyPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const { setSession } = useAuth();
  const verify = useMFAVerify();

  const state = (location.state ?? {}) as MfaNavState;
  const tempSessionId = state.tempSessionId;
  const email = state.email ?? '';
  const initialExpiresAt = useMemo(
    () => (state.expiresAt ? new Date(state.expiresAt) : null),
    [state.expiresAt],
  );

  const [code, setCode] = useState('');
  const [expiresAt, setExpiresAt] = useState<Date | null>(initialExpiresAt);
  const [expired, setExpired] = useState(false);
  const [resendError, setResendError] = useState<ParsedError | null>(null);

  useEffect(() => {
    if (!verify.isSuccess || !verify.data) return;
    setSession(verify.data.accessToken, verify.data.user);
    navigate('/dashboard', { replace: true });
  }, [verify.isSuccess, verify.data, setSession, navigate]);

  useEffect(() => {
    if (verify.error?.code !== 'MFA_SESSION_INVALIDATED') return;
    const id = window.setTimeout(() => navigate('/login', { replace: true }), 3000);
    return () => window.clearTimeout(id);
  }, [verify.error, navigate]);

  if (!tempSessionId || !expiresAt) {
    return <Navigate to="/login" replace />;
  }

  const parsedCode = mfaSchema.safeParse({ code });
  const codeValid = parsedCode.success;

  const onSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!codeValid || expired) return;
    verify.mutate({ tempSessionId, code });
  };

  const onResendSuccess = (response: MfaResendResponse) => {
    setExpiresAt(new Date(Date.now() + response.expiresInSeconds * 1000));
    setExpired(false);
    setCode('');
    setResendError(null);
    verify.reset();
  };

  // ResendButton ya muestra "Alcanzaste el máximo…" permanente dentro de sí mismo cuando llega
  // MAX_RESENDS_EXCEEDED; no duplicamos ese caso con un banner aparte.
  const showResendBanner =
    resendError !== null && resendError.code !== 'MAX_RESENDS_EXCEEDED';
  const resendBannerTone =
    resendError?.code === 'RESEND_COOLDOWN_ACTIVE' ? 'amber' : 'red';

  const errorCode = verify.error?.code;
  const showInvalid = errorCode === 'MFA_INVALID_CODE';
  const showSessionInvalid =
    errorCode === 'MFA_SESSION_INVALIDATED' ||
    errorCode === 'TEMP_SESSION_INVALID';

  return (
    <main className="min-h-screen bg-slate-950 px-4 py-12 text-slate-100">
      <div className="mx-auto w-full max-w-md rounded-xl bg-slate-900/60 p-8 shadow-2xl ring-1 ring-slate-800">
        <header className="mb-6 text-center">
          <h1 className="text-2xl font-bold">Verificá tu identidad</h1>
          <p className="mt-1 text-sm text-slate-400">
            Ingresá el código de 6 dígitos que enviamos a{' '}
            <span className="font-medium text-slate-200">{maskEmail(email)}</span>.
          </p>
        </header>

        <form onSubmit={onSubmit} noValidate className="space-y-5">
          <OTPInput
            value={code}
            onChange={setCode}
            disabled={verify.isPending || expired}
            invalid={showInvalid}
            autoFocus
          />

          <div className="flex items-center justify-between text-sm text-slate-400">
            <span>
              Expira en{' '}
              <Countdown
                expiresAt={expiresAt}
                onExpire={() => setExpired(true)}
              />
            </span>
            <ResendButton
              tempSessionId={tempSessionId}
              onResendSuccess={onResendSuccess}
              onResendError={setResendError}
            />
          </div>

          {verify.error && !showSessionInvalid && (
            <p role="alert" className="text-sm text-red-400">
              {verify.error.message}
            </p>
          )}
          {expired && !verify.error && (
            <p role="alert" className="text-sm text-amber-300">
              El código expiró. Solicitá uno nuevo.
            </p>
          )}
          {showResendBanner && resendError && (
            <p
              role="alert"
              className={
                resendBannerTone === 'amber'
                  ? 'text-sm text-amber-300'
                  : 'text-sm text-red-400'
              }
            >
              {resendError.message}
            </p>
          )}
          {showSessionInvalid && (
            <p
              role="alert"
              className="rounded-md border border-red-700 bg-red-900/40 px-4 py-2 text-sm text-red-200"
            >
              {humanFor(errorCode!)} Te llevamos al login…
            </p>
          )}

          <button
            type="submit"
            disabled={!codeValid || verify.isPending || expired}
            className="w-full rounded-md bg-blue-600 px-4 py-2 font-semibold text-white shadow hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {verify.isPending ? 'Verificando…' : 'Verificar'}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-slate-400">
          <Link to="/login" className="text-blue-400 hover:text-blue-300">
            Volver al login
          </Link>
        </p>
      </div>
    </main>
  );
}

/** Enmascara un email para mostrar "ju***@example.com" (presentación; no se usa en lógica). */
function maskEmail(email: string): string {
  const [name, domain] = email.split('@');
  if (!name || !domain) return email;
  const visible = name.slice(0, 2);
  return `${visible}${'*'.repeat(Math.max(1, name.length - 2))}@${domain}`;
}
