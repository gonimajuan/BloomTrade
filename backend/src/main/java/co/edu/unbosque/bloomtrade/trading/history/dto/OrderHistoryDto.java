package co.edu.unbosque.bloomtrade.trading.history.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Orden histórica del usuario (HU-F17 §6.3 SPEC).
 *
 * <p>Campos numéricos como BigDecimal stringificado scale=2 (plan D13). Campos opcionales
 * pueden ser {@code null} según el {@link OrderStatus}:
 * <ul>
 *   <li>{@code EXECUTED}: todos los campos {@code execution*}/{@code averageFillPrice}/
 *       {@code commission}/{@code alpacaOrderId} poblados; {@code failureReason} null.</li>
 *   <li>{@code PENDING}: {@code alpacaOrderId} puede o no estar (encolada vs en flight);
 *       campos de ejecución null.</li>
 *   <li>{@code REJECTED}/{@code FAILED}: {@code failureReason} poblado con código
 *       machine-readable; campos de ejecución null.</li>
 * </ul>
 */
@Schema(description = "Orden histórica del usuario con su estado y datos de ejecución (si aplica).")
public record OrderHistoryDto(
        UUID orderId,
        UUID clientOrderId,
        @Schema(example = "AAPL") String ticker,
        OrderSide side,
        @Schema(example = "10") int quantity,
        OrderStatus status,
        Instant submittedAt,
        @Schema(nullable = true) Instant executedAt,
        @Schema(
                        description = "Total de ejecución scale=2. Para BUY = cobrado; para SELL = acreditado neto.",
                        example = "1932.00",
                        nullable = true)
                String executionTotal,
        @Schema(
                        description = "Precio de fill promedio scale=2. null si no ejecutada.",
                        example = "193.20",
                        nullable = true)
                String averageFillPrice,
        @Schema(example = "9.66", nullable = true) String commission,
        @Schema(description = "ID en Alpaca paper trading.", nullable = true) String alpacaOrderId,
        @Schema(
                        description =
                                "Código machine-readable del error si status=REJECTED|FAILED.",
                        nullable = true,
                        example = "INSUFFICIENT_FUNDS")
                String failureReason) {}
