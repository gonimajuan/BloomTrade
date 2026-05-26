import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import type { ErrorResponse } from '@/types/api';

/**
 * Cliente HTTP base del frontend (spec HU-F01 §12.3 — reutilizable). baseURL relativa: el proxy
 * de Vite (vite.config.ts) redirige /api → http://localhost:8080 en dev. En prod el frontend
 * sirve desde nginx detrás del mismo origen.
 *
 * <p>En HU-F02 (Lote G) se agregan dos interceptors:
 * <ul>
 *   <li>request: si {@code AuthProvider} registró un getter de access token, lo añade como
 *       {@code Authorization: Bearer ...}.</li>
 *   <li>response: si el backend devuelve 401 con código de token (TOKEN_EXPIRED, TOKEN_INVALID o
 *       TOKEN_REVOKED), invoca el handler de "unauthorized" para que el AuthProvider limpie el
 *       estado y navegue a /login. Decisión D18: sin refresh transparente.</li>
 * </ul>
 *
 * <p>Los interceptors viven en el módulo (singleton); {@link configureAuthInterceptor} actualiza
 * las referencias mutables del módulo cada vez que cambia el access token o el handler.
 */
export const apiClient = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 10_000,
});

// AUTH_REQUIRED se agrega tras cerrar la mini-HU HU-F0X-token-rotation-logout: el backend
// ahora emite 401 AUTH_REQUIRED cuando llega un request anónimo a un endpoint protegido
// (antes era 403 default de Spring Security 6). Tratarlo igual que un token inválido para
// que el interceptor limpie sesión y redirija a /login.
const TOKEN_ERROR_CODES = new Set([
  'TOKEN_EXPIRED',
  'TOKEN_INVALID',
  'TOKEN_REVOKED',
  'AUTH_REQUIRED',
]);

let accessTokenGetter: () => string | null = () => null;
let unauthorizedHandler: () => void = () => {};

/**
 * Conecta los interceptors a la fuente del access token y al handler de "401 token inválido".
 * El AuthProvider invoca esto cada vez que el token cambia.
 */
export function configureAuthInterceptor(opts: {
  getAccessToken: () => string | null;
  onUnauthorized: () => void;
}): void {
  accessTokenGetter = opts.getAccessToken;
  unauthorizedHandler = opts.onUnauthorized;
}

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = accessTokenGetter();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ErrorResponse>) => {
    const status = error.response?.status;
    const code = error.response?.data?.error;
    if (status === 401 && code && TOKEN_ERROR_CODES.has(code)) {
      unauthorizedHandler();
    }
    return Promise.reject(error);
  },
);
