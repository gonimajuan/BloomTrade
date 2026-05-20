import { z } from 'zod';

// Mirror cliente de MfaVerifyRequest del backend (spec HU-F02 §6.1.2).

export const mfaSchema = z.object({
  code: z
    .string()
    .min(1, 'VALIDATION_REQUIRED')
    .regex(/^\d{6}$/, 'VALIDATION_INVALID_OTP'),
});

export type MfaFormValues = z.infer<typeof mfaSchema>;
