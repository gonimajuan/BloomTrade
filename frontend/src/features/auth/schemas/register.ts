import { z } from 'zod';

// Mirror cliente de la validación servidor (spec HU-F01 §6, defensa en profundidad).
// Cada `message` es el código SCREAMING_SNAKE — la UI lo resuelve a ES via humanFor().

const passwordRegex = /^(?=.*[A-Z])(?=.*[a-z])(?=.*\d).{10,100}$/;
const phoneRegex = /^\+[1-9]\d{1,14}$/;
const nameRegex = /^[\p{L} ]{3,100}$/u;
const ccCeRegex = /^\d{6,12}$/;
const pasaporteRegex = /^[A-Za-z0-9]{6,15}$/;

export const registerSchema = z
  .object({
    email: z
      .string()
      .min(1, 'VALIDATION_REQUIRED')
      .max(254, 'VALIDATION_INVALID_EMAIL')
      .email('VALIDATION_INVALID_EMAIL'),
    password: z
      .string()
      .min(1, 'VALIDATION_REQUIRED')
      .regex(passwordRegex, 'WEAK_PASSWORD'),
    nombreCompleto: z
      .string()
      .min(1, 'VALIDATION_REQUIRED')
      .regex(nameRegex, 'VALIDATION_INVALID_NAME'),
    tipoDocumento: z.enum(['CC', 'CE', 'PASAPORTE'], {
      errorMap: () => ({ message: 'VALIDATION_INVALID_DOCUMENT_TYPE' }),
    }),
    numeroDocumento: z.string().min(1, 'VALIDATION_REQUIRED'),
    telefono: z
      .string()
      .min(1, 'VALIDATION_REQUIRED')
      .regex(phoneRegex, 'VALIDATION_INVALID_PHONE'),
    aceptaTerminos: z
      .boolean()
      .refine((v) => v === true, { message: 'TERMS_NOT_ACCEPTED' }),
  })
  .superRefine((data, ctx) => {
    if (!data.numeroDocumento) return; // ya cubierto por VALIDATION_REQUIRED
    const ok =
      data.tipoDocumento === 'PASAPORTE'
        ? pasaporteRegex.test(data.numeroDocumento)
        : ccCeRegex.test(data.numeroDocumento);
    if (!ok) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['numeroDocumento'],
        message: 'VALIDATION_INVALID_DOCUMENT_NUMBER',
      });
    }
  });

export type RegisterFormValues = z.infer<typeof registerSchema>;
