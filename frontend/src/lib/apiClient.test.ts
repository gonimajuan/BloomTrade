import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AxiosHeaders, type AxiosError, type InternalAxiosRequestConfig } from 'axios';
import { apiClient, configureAuthInterceptor } from './apiClient';

/**
 * Tests del jwtInterceptor (HU-F02 Lote I T8.5).
 *
 * <p>Estrategia: el módulo registra sus interceptors una sola vez al cargarse, así que invocamos
 * los handlers directamente desde {@code apiClient.interceptors.{request,response}.handlers}. Es
 * la única forma de testear el behavior sin agregar deps tipo {@code axios-mock-adapter}.
 */
describe('jwtInterceptor', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('request interceptor agrega Authorization: Bearer cuando hay token configurado', () => {
    configureAuthInterceptor({
      getAccessToken: () => 'jwt.signed.token',
      onUnauthorized: vi.fn(),
    });

    const requestHandlers = (apiClient.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: InternalAxiosRequestConfig) => InternalAxiosRequestConfig }>;
    }).handlers;
    expect(requestHandlers.length).toBeGreaterThan(0);

    const config = {
      headers: new AxiosHeaders(),
    } as InternalAxiosRequestConfig;
    const result = requestHandlers[0].fulfilled(config);

    expect(result.headers.get('Authorization')).toBe('Bearer jwt.signed.token');
  });

  it('request interceptor no agrega header cuando getAccessToken devuelve null', () => {
    configureAuthInterceptor({
      getAccessToken: () => null,
      onUnauthorized: vi.fn(),
    });

    const requestHandlers = (apiClient.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: InternalAxiosRequestConfig) => InternalAxiosRequestConfig }>;
    }).handlers;

    const config = {
      headers: new AxiosHeaders(),
    } as InternalAxiosRequestConfig;
    const result = requestHandlers[0].fulfilled(config);

    // AxiosHeaders.get() devuelve undefined (no null) cuando la key no existe.
    expect(result.headers.get('Authorization')).toBeUndefined();
  });

  it('response interceptor invoca onUnauthorized cuando 401 con TOKEN_EXPIRED', async () => {
    const onUnauthorized = vi.fn();
    configureAuthInterceptor({
      getAccessToken: () => null,
      onUnauthorized,
    });

    const responseHandlers = (apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected: (e: unknown) => Promise<unknown> }>;
    }).handlers;
    expect(responseHandlers.length).toBeGreaterThan(0);

    const error = {
      response: {
        status: 401,
        data: { error: 'TOKEN_EXPIRED' },
      },
    } as AxiosError;

    await expect(responseHandlers[0].rejected(error)).rejects.toBe(error);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('response interceptor NO invoca onUnauthorized para errores no relacionados con token', async () => {
    const onUnauthorized = vi.fn();
    configureAuthInterceptor({
      getAccessToken: () => null,
      onUnauthorized,
    });

    const responseHandlers = (apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected: (e: unknown) => Promise<unknown> }>;
    }).handlers;

    // 401 pero con código distinto a TOKEN_* (ej: INVALID_CREDENTIALS del login).
    const error = {
      response: {
        status: 401,
        data: { error: 'INVALID_CREDENTIALS' },
      },
    } as AxiosError;

    await expect(responseHandlers[0].rejected(error)).rejects.toBe(error);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('response interceptor también atrapa TOKEN_INVALID y TOKEN_REVOKED', async () => {
    const onUnauthorized = vi.fn();
    configureAuthInterceptor({
      getAccessToken: () => null,
      onUnauthorized,
    });

    const responseHandlers = (apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected: (e: unknown) => Promise<unknown> }>;
    }).handlers;

    for (const code of ['TOKEN_INVALID', 'TOKEN_REVOKED']) {
      const error = {
        response: { status: 401, data: { error: code } },
      } as AxiosError;
      await expect(responseHandlers[0].rejected(error)).rejects.toBe(error);
    }
    expect(onUnauthorized).toHaveBeenCalledTimes(2);
  });
});
