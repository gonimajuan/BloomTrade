package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.util.UUID;

/**
 * Evento de dominio: orden rechazada (HU-F09 D15, extendido HU-F10 con {@code side}). Razón
 * típica: Alpaca rechazó (qty exceeds buying power, símbolo no soportado en su lado).
 *
 * <p>NO se publica para {@code INSUFFICIENT_FUNDS}, {@code SHORT_SELLING_NOT_ALLOWED} ni
 * {@code INSUFFICIENT_SHARES} — esos son síncronos al usuario antes de llegar a Alpaca, ya los
 * ve en pantalla; no se envía email (SPEC F09 §9.2, SPEC F10 §9.2).
 */
public record OrderRejectedEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        OrderSide side,
        int quantity,
        String reason,
        String alpacaReason) {}
