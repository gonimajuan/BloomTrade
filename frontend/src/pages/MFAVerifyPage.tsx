import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { OTPInput } from '@/components/OTPInput';
import { Countdown } from '@/components/Countdown';
import { ResendButton } from '@/features/auth/components/ResendButton';
import { useMFAVerify } from '@/features/auth/hooks/useMFAVerify';
import { useAuth } from '@/features/auth/context/AuthContext';
import { mfaSchema } from '@/features/auth/schemas/mfa';
import type { ParsedError } from '@/lib/errorParser';
import { humanFor } from '@/lib/messages.es';
import type { MfaResendResponse } from '@/types/api';
import { Card } from '@/components/ui/Card';
import { Button } from '@/components/ui/Button';

interface MfaNavState {
  tempSessionId?: string;
  email?: string;
  expiresAt?: string;
}

/**
 * Página /mfa-verify (spec HU-F02 §12.1 — paso 2 del flujo). Requiere {@code location.state} con
 * el {@code tempSessionId}, {@code email} y {@code expiresAt} que dejó {@link LoginForm}. Sin esos
 * datos redirige a /login.
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

  const showResendBanner =
    resendError !== null && resendError.code !== 'MAX_RESENDS_EXCEEDED';
  const resendBannerTone =
    resendError?.code === 'RESEND_COOLDOWN_ACTIVE' ? 'amber' : 'rose';

  const errorCode = verify.error?.code;
  const showInvalid = errorCode === 'MFA_INVALID_CODE';
  const showSessionInvalid =
    errorCode === 'MFA_SESSION_INVALIDATED' ||
    errorCode === 'TEMP_SESSION_INVALID';

  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-12">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: 'easeOut' }}
        className="w-full max-w-md"
      >
        <div className="mb-6 text-center">
          <div className="mb-3 inline-flex items-center gap-2">
            <span aria-hidden className="text-3xl text-violet-400">
              ❖
            </span>
            <span className="text-2xl font-semibold tracking-tight text-white">
              BloomTrade
            </span>
          </div>
        </div>

        <Card variant="glass-elevated" className="p-8">
          <header className="mb-6">
            <h1 className="text-xl font-semibold text-white">Verificá tu identidad</h1>
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
              <p role="alert" className="text-sm text-rose-300">
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
                    : 'text-sm text-rose-300'
                }
              >
                {resendError.message}
              </p>
            )}
            {showSessionInvalid && (
              <p
                role="alert"
                className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
              >
                {humanFor(errorCode!)} Te llevamos al login…
              </p>
            )}

            <Button
              type="submit"
              disabled={!codeValid || expired}
              isLoading={verify.isPending}
              className="w-full"
            >
              {verify.isPending ? 'Verificando…' : 'Verificar'}
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-slate-400">
            <Link
              to="/login"
              className="font-medium text-violet-300 underline-offset-4 transition-colors hover:text-violet-200 hover:underline"
            >
              Volver al login
            </Link>
          </p>
        </Card>
      </motion.div>
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
