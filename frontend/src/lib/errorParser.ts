import type { AxiosError } from 'axios';
import type { ErrorResponse } from '@/types/api';
import { humanFor } from './messages.es';

/**
 * Forma normalizada del error que consumen los hooks y la UI.
 * Reutilizable por todas las features (introducido en HU-F01 §12.3).
 */
export interface ParsedError {
  /** Código en SCREAMING_SNAKE del backend (D14: cuando hay un solo fieldError, sube acá). */
  code: string;
  /** Texto humano en ES listo para mostrar al usuario. */
  message: string;
  /** Status HTTP devuelto (0 si no hubo respuesta). */
  status: number;
  /** Errores por campo, indexados por nombre del campo. */
  fieldErrors: Record<string, { code: string; message: string }>;
  /** traceId para diagnóstico cuando el backend lo provee. */
  traceId?: string;
  /** Segundos a esperar (header {@code Retry-After}), si el backend lo envió (429 típico). */
  retryAfter?: number;
}

function isAxiosError(err: unknown): err is AxiosError<ErrorResponse> {
  return !!err && typeof err === 'object' && (err as { isAxiosError?: boolean }).isAxiosError === true;
}

export function parseError(error: unknown): ParsedError {
  if (isAxiosError(error)) {
    const data = error.response?.data;
    const retryAfter = readRetryAfter(error);
    if (data && typeof data === 'object' && 'error' in data) {
      const fieldErrors: ParsedError['fieldErrors'] = {};
      for (const fe of data.fieldErrors ?? []) {
        fieldErrors[fe.field] = { code: fe.code, message: humanFor(fe.code) };
      }
      return {
        code: data.error,
        message: humanFor(data.error),
        status: data.status,
        fieldErrors,
        traceId: data.traceId,
        retryAfter,
      };
    }
    return {
      code: 'NETWORK_ERROR',
      message: humanFor('NETWORK_ERROR'),
      status: error.response?.status ?? 0,
      fieldErrors: {},
      retryAfter,
    };
  }
  return {
    code: 'UNKNOWN_ERROR',
    message: humanFor('UNKNOWN_ERROR'),
    status: 0,
    fieldErrors: {},
  };
}

function readRetryAfter(error: AxiosError<ErrorResponse>): number | undefined {
  const raw = error.response?.headers?.['retry-after'];
  if (raw === undefined || raw === null) return undefined;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 ? n : undefined;
}
