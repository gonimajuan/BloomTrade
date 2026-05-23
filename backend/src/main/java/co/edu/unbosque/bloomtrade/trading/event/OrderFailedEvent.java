package co.edu.unbosque.bloomtrade.trading.event;

import java.util.UUID;

/**
 * Evento de dominio: orden falló por error técnico (HU-F09 D15). Causas: Alpaca caída tras 3
 * retries, market data caído entre quote y execution, error interno.
 *
 * <p>Saldo NO se descontó (rollback de la transacción de débito). El listener notifica al
 * usuario que su saldo está intacto.
 */
public record OrderFailedEvent(
        UUID orderId, UUID userId, String ticker, int quantity, String errorCode, String errorMessage) {}
