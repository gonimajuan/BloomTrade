import { z } from 'zod';

// Mirror cliente de LoginRequest del backend (spec HU-F02 §6.1.1). Cada `message` es el código
// SCREAMING_SNAKE; la UI lo resuelve a ES via humanFor(). Defensa en profundidad: el backend
// vuelve a validar y aplica el mismo mapeo D10.

export const loginSchema = z.object({
  email: z
    .string()
    .min(1, 'VALIDATION_REQUIRED')
    .max(254, 'VALIDATION_INVALID_EMAIL')
    .email('VALIDATION_INVALID_EMAIL'),
  password: z
    .string()
    .min(1, 'VALIDATION_REQUIRED')
    .max(100, 'VALIDATION_FAILED'),
});

export type LoginFormValues = z.infer<typeof loginSchema>;
