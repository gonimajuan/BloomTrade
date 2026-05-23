package co.edu.unbosque.bloomtrade.trading.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * Output de {@code POST /api/v1/orders} (HU-F09 §6.1.2). Estado completo de la orden tras
 * la ejecución (sea {@code EXECUTED}, {@code REJECTED} o {@code FAILED}).
 *
 * <p>Campos nullables se omiten del JSON si están vacíos ({@code @JsonInclude(NON_NULL)}):
 * {@code executionUnitPrice}, {@code executionTotal}, {@code alpacaOrderId}, {@code errorCode},
 * {@code errorMessage}, {@code executedAt} solo aparecen cuando aplican.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        UUID id,
        UUID clientOrderId,
        String ticker,
        OrderSide side,
        OrderType type,
        int quantity,
        String quotedUnitPrice,
        String executionUnitPrice,
        String commission,
        String quotedTotal,
        String executionTotal,
        OrderStatus status,
        String alpacaOrderId,
        String errorCode,
        String errorMessage,
        Instant submittedAt,
        Instant executedAt) {}
