package co.edu.unbosque.bloomtrade.trading.event;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.util.UUID;

/**
 * Evento de dominio: orden falló por error técnico (HU-F09 D15, extendido HU-F10 con {@code side}).
 * Causas: Alpaca caída tras 3 retries, market data caído entre quote y execution, error interno.
 *
 * <p>BUY: el saldo NO se descontó (rollback de la transacción de débito). SELL: la posición NO
 * se decrementó (rollback). En ambos casos el listener notifica al usuario que su estado está
 * intacto.
 */
public record OrderFailedEvent(
        UUID orderId,
        UUID userId,
        String ticker,
        OrderSide side,
        int quantity,
        String errorCode,
        String errorMessage) {}
