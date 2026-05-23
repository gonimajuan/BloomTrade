package co.edu.unbosque.bloomtrade.notification.dto;

/**
 * Command para el email "Tu orden de compra fue rechazada por el mercado" (HU-F09 Lote F).
 * NO se envía para rechazos por {@code INSUFFICIENT_FUNDS} — el usuario ya vio el error en
 * pantalla (SPEC §9.2).
 */
public record OrderRejectedEmailCommand(
        String userId,
        String toEmail,
        String nombreCompleto,
        String ticker,
        int quantity,
        String alpacaReason) {}
