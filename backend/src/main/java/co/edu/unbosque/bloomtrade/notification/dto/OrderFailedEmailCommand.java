package co.edu.unbosque.bloomtrade.notification.dto;

/**
 * Command para el email "Tu orden no pudo procesarse — error técnico" (HU-F09 Lote F).
 * Se envía cuando Alpaca cayó tras retries o market data falló. El saldo del usuario NO
 * se descontó — el email lo enfatiza para tranquilidad.
 */
public record OrderFailedEmailCommand(
        String userId,
        String toEmail,
        String nombreCompleto,
        String ticker,
        int quantity,
        String errorMessage) {}
