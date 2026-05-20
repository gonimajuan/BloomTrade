import { describe, it, expect } from 'vitest';
import type { AxiosError, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { parseError } from './errorParser';
import type { ErrorResponse } from '@/types/api';

function axiosError(
  status: number,
  data: ErrorResponse | undefined,
): AxiosError<ErrorResponse> {
  const cfg = {} as InternalAxiosRequestConfig;
  const response = data
    ? ({
        data,
        status,
        statusText: '',
        headers: {},
        config: cfg,
      } as AxiosResponse<ErrorResponse>)
    : undefined;
  return {
    isAxiosError: true,
    response,
    name: 'AxiosError',
    message: '',
    config: cfg,
    toJSON: () => ({}),
  } as AxiosError<ErrorResponse>;
}

describe('parseError', () => {
  it('mapea 409 EMAIL_ALREADY_REGISTERED a top-level code y mensaje humano', () => {
    const err = axiosError(409, {
      timestamp: '2026-05-19T00:00:00Z',
      status: 409,
      error: 'EMAIL_ALREADY_REGISTERED',
      message: 'El correo electrónico ya está registrado',
      path: '/api/v1/auth/register',
      traceId: 't-1',
    });

    const out = parseError(err);

    expect(out.code).toBe('EMAIL_ALREADY_REGISTERED');
    expect(out.status).toBe(409);
    expect(out.message).toContain('correo');
    expect(out.fieldErrors).toEqual({});
    expect(out.traceId).toBe('t-1');
  });

  it('indexa fieldErrors por nombre de campo (D10/D14)', () => {
    const err = axiosError(400, {
      timestamp: '2026-05-19T00:00:00Z',
      status: 400,
      error: 'TERMS_NOT_ACCEPTED',
      message: 'Debe aceptar términos',
      path: '/api/v1/auth/register',
      traceId: 't-2',
      fieldErrors: [
        { field: 'aceptaTerminos', code: 'TERMS_NOT_ACCEPTED', message: 'x' },
      ],
    });

    const out = parseError(err);

    expect(out.fieldErrors.aceptaTerminos.code).toBe('TERMS_NOT_ACCEPTED');
    expect(out.fieldErrors.aceptaTerminos.message).toContain('términos');
  });

  it('cae a NETWORK_ERROR cuando no hay response', () => {
    const out = parseError(axiosError(0, undefined));

    expect(out.code).toBe('NETWORK_ERROR');
    expect(out.fieldErrors).toEqual({});
  });

  it('cae a UNKNOWN_ERROR para no-axios', () => {
    const out = parseError(new Error('boom'));

    expect(out.code).toBe('UNKNOWN_ERROR');
  });
});
