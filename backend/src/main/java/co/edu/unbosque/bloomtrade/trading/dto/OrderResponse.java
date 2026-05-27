package co.edu.unbosque.bloomtrade.trading.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Output de {@code POST /api/v1/orders} (HU-F09 §6.1.2, semántica extendida HU-F10 §6.2.2).
 * Estado completo de la orden tras la ejecución (sea {@code EXECUTED}, {@code REJECTED} o
 * {@code FAILED}).
 *
 * <p>Campos nullables se omiten del JSON si están vacíos ({@code @JsonInclude(NON_NULL)}):
 * {@code executionUnitPrice}, {@code executionTotal}, {@code alpacaOrderId}, {@code errorCode},
 * {@code errorMessage}, {@code executedAt} solo aparecen cuando aplican.
 *
 * <p><b>HU-F10 — semántica side-aware de {@code quotedTotal} y {@code executionTotal}</b>:
 * <ul>
 *   <li><b>BUY</b>: total = {@code subtotal + commission} — lo COBRADO.</li>
 *   <li><b>SELL</b>: total = {@code subtotal − commission} — lo ACREDITADO (producto neto).</li>
 * </ul>
 * Para una orden ejecutada {@code executionTotal} usa {@code execution_unit_price × quantity}
 * con la misma regla side-aware.
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
        @Schema(
                description =
                        "Side-aware: BUY = subtotal + commission (cobrado si ejecuta al precio quote); "
                                + "SELL = subtotal − commission (acreditado si ejecuta al precio quote).")
                String quotedTotal,
        @Schema(
                description =
                        "Side-aware: BUY = execution_unit_price × quantity + commission (cobrado real); "
                                + "SELL = execution_unit_price × quantity − commission (acreditado real).")
                String executionTotal,
        OrderStatus status,
        String alpacaOrderId,
        String errorCode,
        String errorMessage,
        Instant submittedAt,
        Instant executedAt,
        // ─── HU-F15 — Cancelación ────────────────────────────────────────────
        @Schema(description = "Timestamp de la transición CANCELED. NULL si status != CANCELED.")
                Instant canceledAt,
        @Schema(
                description =
                        "Cancel solicitado pero polling Alpaca dio timeout; reconcile lazy v2 materializará. "
                                + "Coexiste con status=PENDING. NULL si no hay cancel pendiente.")
                Instant cancelRequestedAt,
        @Schema(description = "Timestamp de la transición EXPIRED. NULL si status != EXPIRED.")
                Instant expiredAt,
        @Schema(
                description =
                        "Solo BUY canceladas/expiradas: monto refundido al balance (string para precisión BigDecimal).")
                String refundedAmount,
        @Schema(description = "Solo SELL canceladas/expiradas: cantidad restaurada a la posición.")
                Integer restoredQty) {}
