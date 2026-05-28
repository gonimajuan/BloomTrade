import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from 'react-router-dom';
import { useLogin } from '../hooks/useLogin';
import { loginSchema, type LoginFormValues } from '../schemas/login';
import { humanFor } from '@/lib/messages.es';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';

/**
 * Formulario de paso 1 del flujo MFA (spec HU-F02 §12.1). Al hacer submit dispara
 * {@code useLogin}; en success navega a {@code /mfa-verify} con el {@code tempSessionId} y el
 * email en {@code location.state}.
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
          className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
        >
          {mutation.error.message}
        </div>
      )}

      <div>
        <label className="mb-1.5 block text-sm font-medium text-slate-300" htmlFor="email">
          Email
        </label>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          isInvalid={!!errors.email}
          {...register('email')}
        />
        {errors.email && (
          <p className="mt-1.5 text-xs text-rose-300" role="alert">
            {humanFor(errors.email.message ?? '')}
          </p>
        )}
      </div>

      <div>
        <label className="mb-1.5 block text-sm font-medium text-slate-300" htmlFor="password">
          Password
        </label>
        <Input
          id="password"
          type="password"
          autoComplete="current-password"
          isInvalid={!!errors.password}
          {...register('password')}
        />
        {errors.password && (
          <p className="mt-1.5 text-xs text-rose-300" role="alert">
            {humanFor(errors.password.message ?? '')}
          </p>
        )}
      </div>

      <Button
        type="submit"
        disabled={!isValid}
        isLoading={mutation.isPending}
        className="w-full"
      >
        {mutation.isPending ? 'Verificando…' : 'Iniciar sesión'}
      </Button>
    </form>
  );
}
