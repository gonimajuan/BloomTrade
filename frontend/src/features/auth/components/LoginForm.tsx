import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from 'react-router-dom';
import { useLogin } from '../hooks/useLogin';
import { loginSchema, type LoginFormValues } from '../schemas/login';
import { humanFor } from '@/lib/messages.es';

const INPUT =
  'w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100 placeholder:text-slate-500 focus:border-blue-500 focus:outline-none disabled:opacity-50';
const LABEL = 'block text-sm text-slate-300';
const ERR = 'mt-1 text-xs text-red-400';

/**
 * Formulario de paso 1 del flujo MFA (spec HU-F02 §12.1). Al hacer submit dispara
 * {@code useLogin}; en success navega a {@code /mfa-verify} con el {@code tempSessionId} y el
 * email en {@code location.state} para que la página de MFA tenga el contexto sin tener que
 * persistirlo en otra capa.
 */
export function LoginForm() {
  const navigate = useNavigate();
  const mutation = useLogin();

  const {
    register,
    handleSubmit,
    getValues,
    formState: { errors, isValid },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    mode: 'onChange',
    defaultValues: { email: '', password: '' },
  });

  useEffect(() => {
    if (!mutation.isSuccess || !mutation.data) return;
    const expiresAt = new Date(Date.now() + mutation.data.expiresInSeconds * 1000);
    navigate('/mfa-verify', {
      replace: true,
      state: {
        tempSessionId: mutation.data.tempSessionId,
        email: getValues('email'),
        expiresAt: expiresAt.toISOString(),
      },
    });
  }, [mutation.isSuccess, mutation.data, navigate, getValues]);

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {mutation.error && (
        <div
          role="alert"
          className="rounded-md border border-red-700 bg-red-900/40 px-4 py-2 text-sm text-red-200"
        >
          {mutation.error.message}
        </div>
      )}

      <div>
        <label className={LABEL} htmlFor="email">Email</label>
        <input
          id="email"
          className={INPUT}
          type="email"
          autoComplete="email"
          {...register('email')}
        />
        {errors.email && (
          <p className={ERR} role="alert">{humanFor(errors.email.message ?? '')}</p>
        )}
      </div>

      <div>
        <label className={LABEL} htmlFor="password">Password</label>
        <input
          id="password"
          className={INPUT}
          type="password"
          autoComplete="current-password"
          {...register('password')}
        />
        {errors.password && (
          <p className={ERR} role="alert">{humanFor(errors.password.message ?? '')}</p>
        )}
      </div>

      <button
        type="submit"
        disabled={!isValid || mutation.isPending}
        className="w-full rounded-md bg-blue-600 px-4 py-2 font-semibold text-white shadow hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {mutation.isPending ? 'Verificando…' : 'Iniciar sesión'}
      </button>
    </form>
  );
}
