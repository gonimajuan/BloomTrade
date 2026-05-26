/**
 * Helpers centralizados para formato de fecha/hora con timezone visible.
 *
 * <p>P1-3 del audit single-user MVP (2026-05-25): los componentes mostraban timestamps en
 * hora local del navegador SIN indicador de timezone, confundiendo al usuario sobre si era
 * hora server (UTC) o local. Centralizar aquí garantiza:
 * <ul>
 *   <li>Locale {@code es-CO} consistente con {@code messages.es.ts}.</li>
 *   <li>{@code timeZoneName: 'short'} añade el offset (ej "GMT-5") al final del string.</li>
 * </ul>
 *
 * <p>Usar estas funciones en lugar de {@code new Date(iso).toLocaleString()} o
 * {@code date-fns format(...)} cuando se muestre un timestamp absoluto al usuario. Para
 * tiempos relativos ("hace 3 minutos") seguir usando {@code formatDistanceToNow} de date-fns.
 */

const dateTimeFormatter = new Intl.DateTimeFormat('es-CO', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  timeZoneName: 'short',
});

const dateTimeWithSecondsFormatter = new Intl.DateTimeFormat('es-CO', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  timeZoneName: 'short',
});

/**
 * Formatea un ISO 8601 al locale es-CO con timezone visible al final.
 * Ejemplo: "23 may 2026, 09:42 GMT-5".
 */
export function formatLocalDateTime(iso: string): string {
  return dateTimeFormatter.format(new Date(iso));
}

/** Variante con segundos, para timestamps de mayor precisión (ej quotedAt). */
export function formatLocalDateTimeWithSeconds(iso: string): string {
  return dateTimeWithSecondsFormatter.format(new Date(iso));
}
