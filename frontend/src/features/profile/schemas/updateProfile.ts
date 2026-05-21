import { z } from 'zod';
import { ALL_TICKERS, MAX_TICKERS } from '@/constants/tickers';

// Paralelo a backend UpdateProfileRequest. Los `message` son códigos SCREAMING_SNAKE
// (decisión D9 HU-F02 / D10 HU-F01); el `humanFor` resuelve a texto humano en la UI.

const phoneE164 = /^\+[1-9]\d{1,14}$/;

export const updateProfileSchema = z.object({
  nombreCompleto: z
    .string()
    .min(3, { message: 'VALIDATION_INVALID_NAME' })
    .max(100, { message: 'VALIDATION_INVALID_NAME' })
    .optional(),
  telefono: z
    .string()
    .regex(phoneE164, { message: 'VALIDATION_INVALID_PHONE' })
    .optional(),
  notificationChannel: z
    .enum(['EMAIL', 'SMS', 'WHATSAPP'], {
      errorMap: () => ({ message: 'VALIDATION_INVALID_CHANNEL' }),
    })
    .optional(),
  tickersOfInterest: z
    .array(
      z.enum(ALL_TICKERS as unknown as [string, ...string[]], {
        errorMap: () => ({ message: 'INVALID_TICKER' }),
      }),
    )
    .max(MAX_TICKERS, { message: 'TOO_MANY_TICKERS' })
    .refine((arr) => new Set(arr).size === arr.length, {
      message: 'DUPLICATE_TICKERS',
    })
    .optional(),
});

export type UpdateProfileFormValues = z.infer<typeof updateProfileSchema>;
