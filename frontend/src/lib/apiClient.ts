import axios from 'axios';

/**
 * Cliente HTTP base del frontend (spec HU-F01 §12.3 — reutilizable). baseURL relativa: el proxy
 * de Vite (vite.config.ts) redirige /api → http://localhost:8080 en dev. En prod el frontend
 * sirve desde nginx detrás del mismo origen.
 */
export const apiClient = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 10_000,
});
