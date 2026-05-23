package co.edu.unbosque.bloomtrade.trading.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import java.time.Instant;

/**
 * Output de {@code POST /api/v1/orders/quote} (HU-F09 §6.1.1).
 *
 * <p>Todos los montos van como {@link String} (no {@code BigDecimal}) para preservar precisión
 * en el JSON serializado: Jackson convierte {@code BigDecimal} a {@code number} y puede sufrir
 * pérdida en el lado cliente (JS number = double). El frontend formatea con la string tal cual.
 */
public record QuoteResponse(
        String ticker,
        OrderSide side,
        int quantity,
        String estimatedUnitPrice,
        String estimatedSubtotal,
        String commission,
        String estimatedTotal,
        String currency,
        String userBalance,
        boolean sufficientFunds,
        boolean marketOpen,
        Instant quotedAt) {}
