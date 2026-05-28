import { useEffect, useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { motion, type Variants } from 'framer-motion';
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
import { Card } from '@/components/ui/Card';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { cn } from '@/lib/cn';
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

const container: Variants = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: { staggerChildren: 0.08, delayChildren: 0.05 },
  },
};
const item: Variants = {
  hidden: { opacity: 0, y: 16 },
  show: { opacity: 1, y: 0, transition: { duration: 0.4, ease: 'easeOut' } },
};

export function ProfilePage() {
  const { data: profile, isLoading, error } = useProfile();

  if (isLoading) {
    return (
      <>
        <AppHeader />
        <main className="mx-auto max-w-2xl px-6 py-10 text-sm text-slate-400">
          Cargando perfil…
        </main>
      </>
    );
  }

  if (error || !profile) {
    return (
      <>
        <AppHeader />
        <main className="mx-auto max-w-2xl px-6 py-10">
          <Card
            variant="glass"
            className="border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
          >
            No se pudo cargar tu perfil. Recargá la página.
          </Card>
        </main>
      </>
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

  useEffect(() => {
    reset(defaultValues);
  }, [defaultValues, reset]);

  const onSubmit = handleSubmit(async (values) => {
    setBannerError(null);
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
    <>
      <AppHeader />
      <main className="mx-auto max-w-3xl px-6 py-10">
        <motion.header
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, ease: 'easeOut' }}
          className="mb-8"
        >
          <h1 className="text-3xl font-semibold tracking-tight text-white">Mi perfil</h1>
          <p className="mt-1.5 text-sm text-slate-400">
            Actualizá tu nombre, teléfono, canal de notificación y tickers de interés.
          </p>
        </motion.header>

        {bannerError && (
          <Card
            variant="glass"
            className="mb-4 border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-200"
            role="alert"
          >
            {bannerError}
          </Card>
        )}

        {mutation.isSuccess && !isDirty && (
          <Card
            variant="glass"
            className="mb-4 border-emerald-500/30 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-200"
          >
            Cambios guardados
          </Card>
        )}

        <form onSubmit={onSubmit}>
          <motion.div
            variants={container}
            initial="hidden"
            animate="show"
            className="space-y-6"
          >
            <motion.div variants={item}>
              <Card variant="glass" className="p-6">
                <h2 className="text-xs font-medium uppercase tracking-[0.2em] text-slate-400">
                  Información personal
                </h2>
                <dl className="mt-5 grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <ReadOnlyField label="Email" value={profile.email} />
                  <ReadOnlyField label="Tipo de documento" value={profile.tipoDocumento} />
                  <ReadOnlyField
                    label="Número de documento"
                    value={profile.numeroDocumento}
                  />
                  <div className="sm:col-span-2">
                    <label
                      htmlFor="nombreCompleto"
                      className="mb-1.5 block text-sm font-medium text-slate-300"
                    >
                      Nombre completo
                    </label>
                    <Input
                      id="nombreCompleto"
                      type="text"
                      isInvalid={!!errors.nombreCompleto}
                      {...register('nombreCompleto')}
                    />
                    {errors.nombreCompleto?.message && (
                      <p className="mt-1.5 text-xs text-rose-300">
                        {humanFor(errors.nombreCompleto.message)}
                      </p>
                    )}
                  </div>
                  <div className="sm:col-span-2">
                    <label
                      htmlFor="telefono"
                      className="mb-1.5 block text-sm font-medium text-slate-300"
                    >
                      Teléfono (formato E.164)
                    </label>
                    <Input
                      id="telefono"
                      type="tel"
                      placeholder="+573001234567"
                      isInvalid={!!errors.telefono}
                      {...register('telefono')}
                    />
                    {errors.telefono?.message && (
                      <p className="mt-1.5 text-xs text-rose-300">
                        {humanFor(errors.telefono.message)}
                      </p>
                    )}
                  </div>
                </dl>
              </Card>
            </motion.div>

            <motion.div variants={item}>
              <Card variant="glass" className="p-6">
                <h2 className="text-xs font-medium uppercase tracking-[0.2em] text-slate-400">
                  Canal preferido de notificación
                </h2>
                <Controller
                  control={control}
                  name="notificationChannel"
                  render={({ field }) => (
                    <div className="mt-4 grid grid-cols-3 gap-2" role="radiogroup">
                      {CHANNELS.map((c) => (
                        <label
                          key={c.value}
                          className={cn(
                            'flex cursor-pointer items-center justify-center gap-2 rounded-xl border px-4 py-2.5 text-sm font-medium transition-all',
                            field.value === c.value
                              ? 'border-violet-500/50 bg-violet-500/15 text-violet-200 shadow-glow-violet-sm'
                              : 'border-white/10 bg-slate-900/40 text-slate-300 hover:border-white/20',
                          )}
                        >
                          <input
                            type="radio"
                            name={field.name}
                            value={c.value}
                            checked={field.value === c.value}
                            onChange={() => field.onChange(c.value)}
                            className="sr-only"
                          />
                          {c.label}
                        </label>
                      ))}
                    </div>
                  )}
                />
              </Card>
            </motion.div>

            <motion.div variants={item}>
              <Card variant="glass" className="p-6">
                <div className="flex items-end justify-between">
                  <h2 className="text-xs font-medium uppercase tracking-[0.2em] text-slate-400">
                    Mercados y acciones de interés
                  </h2>
                  <span className="text-xs text-slate-500 tabular-nums">
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
                          <h3 className="mb-2 inline-flex items-center rounded-full bg-violet-500/15 px-2.5 py-0.5 text-[10px] font-bold uppercase tracking-[0.15em] text-violet-200">
                            {market}
                          </h3>
                          <div className="flex flex-wrap gap-2">
                            {ALLOWED_TICKERS[market].map((ticker) => {
                              const checked = field.value?.includes(ticker) ?? false;
                              return (
                                <label
                                  key={ticker}
                                  className={cn(
                                    'flex cursor-pointer items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-all',
                                    checked
                                      ? 'border-violet-500/50 bg-violet-500/15 text-violet-200'
                                      : 'border-white/10 bg-slate-800/40 text-slate-300 hover:border-white/20 hover:text-white',
                                  )}
                                >
                                  <input
                                    type="checkbox"
                                    className="sr-only"
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
                  <p className="mt-3 text-xs text-rose-300">
                    {humanFor(errors.tickersOfInterest.message)}
                  </p>
                )}
              </Card>
            </motion.div>

            <motion.div
              variants={item}
              className="flex justify-end gap-2 border-t border-white/10 pt-6"
            >
              <Button
                variant="ghost"
                size="md"
                onClick={onCancel}
                disabled={!isDirty || mutation.isPending}
              >
                Cancelar
              </Button>
              <Button
                type="submit"
                variant="primary"
                size="md"
                disabled={!isDirty || !isValid}
                isLoading={mutation.isPending}
              >
                {mutation.isPending ? 'Guardando…' : 'Guardar cambios'}
              </Button>
            </motion.div>
          </motion.div>
        </form>

        <DiscardChangesModal
          open={discard.isOpen}
          onConfirm={discard.confirmDiscard}
          onCancel={discard.cancelDiscard}
        />
      </main>
    </>
  );
}

function ReadOnlyField({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="mb-1.5 text-sm font-medium text-slate-300">{label}</dt>
      <dd className="rounded-xl border border-white/5 bg-slate-900/30 px-4 py-2.5 text-sm text-slate-400">
        {value}
        <span className="ml-2 text-xs text-slate-600">(no editable)</span>
      </dd>
    </div>
  );
}
