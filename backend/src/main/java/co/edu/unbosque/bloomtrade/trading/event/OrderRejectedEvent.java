package co.edu.unbosque.bloomtrade.trading.event;

import java.util.UUID;

/**
 * Evento de dominio: orden rechazada (HU-F09 D15). Razón típica: Alpaca rechazó (qty exceeds
 * buying power, símbolo no soportado en su lado).
 *
 * <p>NO se publica para {@code INSUFFICIENT_FUNDS} — ese es síncrono al usuario antes de
 * llegar a Alpaca, ya lo ve en pantalla; no se envía email (SPEC §9.2).
 */
public record OrderRejectedEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        int quantity,
        String reason,
        String alpacaReason) {}
