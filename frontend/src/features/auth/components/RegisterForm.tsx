import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useNavigate } from 'react-router-dom';
import { useRegister } from '../hooks/useRegister';
import { registerSchema, type RegisterFormValues } from '../schemas/register';
import { humanFor } from '@/lib/messages.es';
import { PasswordStrengthIndicator } from '@/components/PasswordStrengthIndicator';
import { PhoneInput } from '@/components/PhoneInput';
import { TermsCheckbox } from './TermsCheckbox';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { cn } from '@/lib/cn';

const LABEL = 'mb-1.5 block text-sm font-medium text-slate-300';
const ERR = 'mt-1.5 text-xs text-rose-300';

const SELECT_BASE =
  'block w-full rounded-xl border bg-slate-900/60 px-4 py-2.5 text-sm text-slate-100 backdrop-blur-sm transition-colors';
const SELECT_DEFAULT = 'border-white/10 hover:border-white/20';
const SELECT_FOCUS =
  'focus:border-violet-400/50 focus:outline-none focus:ring-2 focus:ring-violet-400/50';

/** Formulario de registro (spec HU-F01 §12.1). RHF + zod + mensajes humanos por código. */
export function RegisterForm() {
  const navigate = useNavigate();
  const mutation = useRegister();

  const {
    register,
    handleSubmit,
    watch,
    setError,
    formState: { errors, isValid },
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(registerSchema),
    mode: 'onChange',
    defaultValues: {
      email: '',
      password: '',
      nombreCompleto: '',
      tipoDocumento: 'CC',
      numeroDocumento: '',
      telefono: '+57',
      aceptaTerminos: false,
    },
  });

  const password = watch('password') ?? '';

  useEffect(() => {
    if (!mutation.error) return;
    for (const [field, value] of Object.entries(mutation.error.fieldErrors)) {
      setError(field as keyof RegisterFormValues, {
        type: 'server',
        message: value.code,
      });
    }
  }, [mutation.error, setError]);

  useEffect(() => {
    if (!mutation.isSuccess) return;
    const t = setTimeout(() => navigate('/login'), 1500);
    return () => clearTimeout(t);
  }, [mutation.isSuccess, navigate]);

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  const banner =
    mutation.error && Object.keys(mutation.error.fieldErrors).length === 0
      ? mutation.error.message
      : null;

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {banner && (
        <div
          role="alert"
          className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
        >
          {banner}
        </div>
      )}
      {mutation.isSuccess && (
        <div
          role="status"
          className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-200"
        >
          Cuenta creada exitosamente. Te llevamos al login…
        </div>
      )}

      <div>
        <label className={LABEL} htmlFor="email">
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
          <p className={ERR} role="alert">
            {humanFor(errors.email.message ?? '')}
          </p>
        )}
      </div>

      <div>
        <label className={LABEL} htmlFor="password">
          Password
        </label>
        <Input
          id="password"
          type="password"
          autoComplete="new-password"
          isInvalid={!!errors.password}
          {...register('password')}
        />
        <PasswordStrengthIndicator password={password} />
        {errors.password && (
          <p className={ERR} role="alert">
            {humanFor(errors.password.message ?? '')}
          </p>
        )}
      </div>

      <div>
        <label className={LABEL} htmlFor="nombreCompleto">
          Nombre completo
        </label>
        <Input
          id="nombreCompleto"
          type="text"
          autoComplete="name"
          isInvalid={!!errors.nombreCompleto}
          {...register('nombreCompleto')}
        />
        {errors.nombreCompleto && (
          <p className={ERR} role="alert">
            {humanFor(errors.nombreCompleto.message ?? '')}
          </p>
        )}
      </div>

      <div className="grid grid-cols-3 gap-3">
        <div>
          <label className={LABEL} htmlFor="tipoDocumento">
            Tipo doc.
          </label>
          <select
            id="tipoDocumento"
            className={cn(SELECT_BASE, SELECT_DEFAULT, SELECT_FOCUS)}
            {...register('tipoDocumento')}
          >
            <option value="CC">CC</option>
            <option value="CE">CE</option>
            <option value="PASAPORTE">Pasaporte</option>
          </select>
        </div>
        <div className="col-span-2">
          <label className={LABEL} htmlFor="numeroDocumento">
            Número documento
          </label>
          <Input
            id="numeroDocumento"
            type="text"
            isInvalid={!!errors.numeroDocumento}
            {...register('numeroDocumento')}
          />
          {errors.numeroDocumento && (
            <p className={ERR} role="alert">
              {humanFor(errors.numeroDocumento.message ?? '')}
            </p>
          )}
        </div>
      </div>

      <div>
        <label className={LABEL} htmlFor="telefono">
          Teléfono
        </label>
        <PhoneInput
          id="telefono"
          isInvalid={!!errors.telefono}
          {...register('telefono')}
        />
        {errors.telefono && (
          <p className={ERR} role="alert">
            {humanFor(errors.telefono.message ?? '')}
          </p>
        )}
      </div>

      <div>
        <TermsCheckbox {...register('aceptaTerminos')} />
        {errors.aceptaTerminos && (
          <p className={ERR} role="alert">
            {humanFor(errors.aceptaTerminos.message ?? '')}
          </p>
        )}
      </div>

      <Button
        type="submit"
        disabled={!isValid}
        isLoading={mutation.isPending}
        className="w-full"
      >
        {mutation.isPending ? 'Creando cuenta…' : 'Crear mi cuenta'}
      </Button>
    </form>
  );
}
