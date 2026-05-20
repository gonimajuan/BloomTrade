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

const INPUT =
  'w-full rounded-md border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100 placeholder:text-slate-500 focus:border-blue-500 focus:outline-none disabled:opacity-50';

const LABEL = 'block text-sm text-slate-300';
const ERR = 'mt-1 text-xs text-red-400';

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

  // Si el backend devuelve fieldErrors, se aplican al campo correspondiente.
  useEffect(() => {
    if (!mutation.error) return;
    for (const [field, value] of Object.entries(mutation.error.fieldErrors)) {
      setError(field as keyof RegisterFormValues, {
        type: 'server',
        message: value.code,
      });
    }
  }, [mutation.error, setError]);

  // Post-201: redirige a /login después de 1.5s (spec §12.1).
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
          className="rounded-md border border-red-700 bg-red-900/40 px-4 py-2 text-sm text-red-200"
        >
          {banner}
        </div>
      )}
      {mutation.isSuccess && (
        <div
          role="status"
          className="rounded-md border border-emerald-700 bg-emerald-900/40 px-4 py-2 text-sm text-emerald-200"
        >
          Cuenta creada exitosamente. Te llevamos al login…
        </div>
      )}

      <div>
        <label className={LABEL} htmlFor="email">Email</label>
        <input id="email" className={INPUT} type="email" autoComplete="email" {...register('email')} />
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
          autoComplete="new-password"
          {...register('password')}
        />
        <PasswordStrengthIndicator password={password} />
        {errors.password && (
          <p className={ERR} role="alert">{humanFor(errors.password.message ?? '')}</p>
        )}
      </div>

      <div>
        <label className={LABEL} htmlFor="nombreCompleto">Nombre completo</label>
        <input
          id="nombreCompleto"
          className={INPUT}
          type="text"
          autoComplete="name"
          {...register('nombreCompleto')}
        />
        {errors.nombreCompleto && (
          <p className={ERR} role="alert">{humanFor(errors.nombreCompleto.message ?? '')}</p>
        )}
      </div>

      <div className="grid grid-cols-3 gap-3">
        <div>
          <label className={LABEL} htmlFor="tipoDocumento">Tipo doc.</label>
          <select id="tipoDocumento" className={INPUT} {...register('tipoDocumento')}>
            <option value="CC">CC</option>
            <option value="CE">CE</option>
            <option value="PASAPORTE">Pasaporte</option>
          </select>
        </div>
        <div className="col-span-2">
          <label className={LABEL} htmlFor="numeroDocumento">Número documento</label>
          <input
            id="numeroDocumento"
            className={INPUT}
            type="text"
            {...register('numeroDocumento')}
          />
          {errors.numeroDocumento && (
            <p className={ERR} role="alert">{humanFor(errors.numeroDocumento.message ?? '')}</p>
          )}
        </div>
      </div>

      <div>
        <label className={LABEL} htmlFor="telefono">Teléfono</label>
        <PhoneInput id="telefono" className={INPUT} {...register('telefono')} />
        {errors.telefono && (
          <p className={ERR} role="alert">{humanFor(errors.telefono.message ?? '')}</p>
        )}
      </div>

      <div>
        <TermsCheckbox {...register('aceptaTerminos')} />
        {errors.aceptaTerminos && (
          <p className={ERR} role="alert">{humanFor(errors.aceptaTerminos.message ?? '')}</p>
        )}
      </div>

      <button
        type="submit"
        disabled={!isValid || mutation.isPending}
        className="w-full rounded-md bg-blue-600 px-4 py-2 font-semibold text-white shadow hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {mutation.isPending ? 'Creando cuenta…' : 'Crear mi cuenta'}
      </button>
    </form>
  );
}
