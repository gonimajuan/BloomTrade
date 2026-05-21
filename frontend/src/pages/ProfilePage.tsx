import { useEffect, useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { AppHeader } from '@/components/AppHeader';
import { DiscardChangesModal } from '@/components/DiscardChangesModal';
import {
  ALLOWED_TICKERS,
  MARKETS,
  MAX_TICKERS,
  type Market,
} from '@/constants/tickers';
import { useProfile } from '@/features/profile/hooks/useProfile';
import { useUpdateProfile } from '@/features/profile/hooks/useUpdateProfile';
import { useDiscardChangesPrompt } from '@/features/profile/hooks/useDiscardChangesPrompt';
import {
  updateProfileSchema,
  type UpdateProfileFormValues,
} from '@/features/profile/schemas/updateProfile';
import { humanFor } from '@/lib/messages.es';
import type {
  NotificationChannel,
  UpdateProfileRequest,
  UserProfileResponse,
} from '@/types/api';

const CHANNELS: { value: NotificationChannel; label: string }[] = [
  { value: 'EMAIL', label: 'Email' },
  { value: 'SMS', label: 'SMS' },
  { value: 'WHATSAPP', label: 'WhatsApp' },
];

export function ProfilePage() {
  const { data: profile, isLoading, error } = useProfile();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50">
        <AppHeader />
        <main className="mx-auto max-w-2xl px-6 py-10 text-slate-500">
          Cargando perfil…
        </main>
      </div>
    );
  }

  if (error || !profile) {
    return (
      <div className="min-h-screen bg-slate-50">
        <AppHeader />
        <main className="mx-auto max-w-2xl px-6 py-10 text-red-600">
          No se pudo cargar tu perfil. Recarga la página.
        </main>
      </div>
    );
  }

  return <ProfileForm profile={profile} />;
}

function ProfileForm({ profile }: { profile: UserProfileResponse }) {
  const mutation = useUpdateProfile();
  const [bannerError, setBannerError] = useState<string | null>(null);

  const defaultValues: UpdateProfileFormValues = useMemo(
    () => ({
      nombreCompleto: profile.nombreCompleto,
      telefono: profile.telefono,
      notificationChannel: profile.notificationChannel,
      tickersOfInterest: [...profile.tickersOfInterest],
    }),
    [profile],
  );

  const {
    register,
    control,
    handleSubmit,
    reset,
    watch,
    formState: { errors, isDirty, isValid, dirtyFields },
  } = useForm<UpdateProfileFormValues>({
    defaultValues,
    resolver: zodResolver(updateProfileSchema),
    mode: 'onChange',
  });

  const tickers = watch('tickersOfInterest') ?? [];
  const discard = useDiscardChangesPrompt(isDirty);

  // Re-inicializa el form si llega un perfil nuevo (post-mutation success).
  useEffect(() => {
    reset(defaultValues);
  }, [defaultValues, reset]);

  const onSubmit = handleSubmit(async (values) => {
    setBannerError(null);
    // Enviamos solo los campos efectivamente modificados (semántica PATCH parcial).
    const payload: UpdateProfileRequest = {};
    if (dirtyFields.nombreCompleto) payload.nombreCompleto = values.nombreCompleto;
    if (dirtyFields.telefono) payload.telefono = values.telefono;
    if (dirtyFields.notificationChannel)
      payload.notificationChannel = values.notificationChannel;
    if (dirtyFields.tickersOfInterest)
      payload.tickersOfInterest = values.tickersOfInterest;

    try {
      await mutation.mutateAsync(payload);
    } catch (err) {
      const parsed = err as { error?: string; message?: string };
      setBannerError(parsed.message ?? humanFor(parsed.error ?? 'UNKNOWN_ERROR'));
    }
  });

  const onCancel = () => {
    discard.request(() => reset(defaultValues));
  };

  return (
    <div className="min-h-screen bg-slate-50">
      <AppHeader />
      <main className="mx-auto max-w-2xl px-6 py-10">
        <h1 className="text-2xl font-semibold text-slate-900">Mi perfil</h1>

        {bannerError && (
          <div
            role="alert"
            className="mt-4 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
          >
            {bannerError}
          </div>
        )}

        {mutation.isSuccess && !isDirty && (
          <div className="mt-4 rounded-md border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
            Cambios guardados
          </div>
        )}

        <form onSubmit={onSubmit} className="mt-6 space-y-8">
          {/* Información personal */}
          <section>
            <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
              Información personal
            </h2>
            <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
              <ReadOnlyField label="Email" value={profile.email} />
              <ReadOnlyField
                label="Tipo de documento"
                value={profile.tipoDocumento}
              />
              <ReadOnlyField
                label="Número de documento"
                value={profile.numeroDocumento}
              />
              <div className="sm:col-span-2">
                <label
                  htmlFor="nombreCompleto"
                  className="block text-sm font-medium text-slate-700"
                >
                  Nombre completo
                </label>
                <input
                  id="nombreCompleto"
                  type="text"
                  {...register('nombreCompleto')}
                  className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 shadow-sm focus:border-slate-500 focus:outline-none"
                />
                {errors.nombreCompleto?.message && (
                  <p className="mt-1 text-sm text-red-600">
                    {humanFor(errors.nombreCompleto.message)}
                  </p>
                )}
              </div>
              <div className="sm:col-span-2">
                <label
                  htmlFor="telefono"
                  className="block text-sm font-medium text-slate-700"
                >
                  Teléfono (formato E.164)
                </label>
                <input
                  id="telefono"
                  type="tel"
                  placeholder="+573001234567"
                  {...register('telefono')}
                  className="mt-1 block w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 shadow-sm focus:border-slate-500 focus:outline-none"
                />
                {errors.telefono?.message && (
                  <p className="mt-1 text-sm text-red-600">
                    {humanFor(errors.telefono.message)}
                  </p>
                )}
              </div>
            </dl>
          </section>

          {/* Canal de notificación */}
          <section>
            <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
              Canal preferido de notificación
            </h2>
            <Controller
              control={control}
              name="notificationChannel"
              render={({ field }) => (
                <div className="mt-4 flex gap-6" role="radiogroup">
                  {CHANNELS.map((c) => (
                    <label
                      key={c.value}
                      className="flex items-center gap-2 text-sm text-slate-700"
                    >
                      <input
                        type="radio"
                        name={field.name}
                        value={c.value}
                        checked={field.value === c.value}
                        onChange={() => field.onChange(c.value)}
                      />
                      {c.label}
                    </label>
                  ))}
                </div>
              )}
            />
          </section>

          {/* Tickers de interés */}
          <section>
            <div className="flex items-end justify-between">
              <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">
                Mercados y acciones de interés
              </h2>
              <span className="text-xs text-slate-500">
                {tickers.length} de {MAX_TICKERS} seleccionados
              </span>
            </div>
            <Controller
              control={control}
              name="tickersOfInterest"
              render={({ field }) => (
                <div className="mt-4 space-y-4">
                  {MARKETS.map((market: Market) => (
                    <div key={market}>
                      <h3 className="text-xs font-semibold text-slate-600">
                        {market}
                      </h3>
                      <div className="mt-2 flex flex-wrap gap-3">
                        {ALLOWED_TICKERS[market].map((ticker) => {
                          const checked = field.value?.includes(ticker) ?? false;
                          return (
                            <label
                              key={ticker}
                              className="flex items-center gap-1.5 rounded border border-slate-200 bg-white px-2 py-1 text-sm text-slate-700"
                            >
                              <input
                                type="checkbox"
                                checked={checked}
                                onChange={(e) => {
                                  const current = field.value ?? [];
                                  if (e.target.checked) {
                                    if (current.length >= MAX_TICKERS) return;
                                    field.onChange([...current, ticker]);
                                  } else {
                                    field.onChange(
                                      current.filter((t) => t !== ticker),
                                    );
                                  }
                                }}
                              />
                              {ticker}
                            </label>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            />
            {errors.tickersOfInterest?.message && (
              <p className="mt-2 text-sm text-red-600">
                {humanFor(errors.tickersOfInterest.message)}
              </p>
            )}
          </section>

          {/* Save / Cancel */}
          <div className="flex justify-end gap-3 border-t border-slate-200 pt-6">
            <button
              type="button"
              onClick={onCancel}
              disabled={!isDirty || mutation.isPending}
              className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-40"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={!isDirty || !isValid || mutation.isPending}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-40"
            >
              {mutation.isPending ? 'Guardando…' : 'Guardar cambios'}
            </button>
          </div>
        </form>

        <DiscardChangesModal
          open={discard.isOpen}
          onConfirm={discard.confirmDiscard}
          onCancel={discard.cancelDiscard}
        />
      </main>
    </div>
  );
}

function ReadOnlyField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-sm font-medium text-slate-700">{label}</dt>
      <dd className="mt-1 rounded-md bg-slate-100 px-3 py-2 text-sm text-slate-500">
        {value}
        <span className="ml-2 text-xs text-slate-400">(no editable)</span>
      </dd>
    </div>
  );
}
