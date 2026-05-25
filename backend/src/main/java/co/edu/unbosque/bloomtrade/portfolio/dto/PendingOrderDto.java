package co.edu.unbosque.bloomtrade.portfolio.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * Ítem del array {@code pendingOrders[]} en {@link PortfolioPositionsResponse} (HU-F16 §6.2).
 * Representa una orden que Alpaca aceptó pero aún no liquidó (estado
 * {@code PENDING + alpacaOrderId != null}, típicamente fuera de horario de mercado).
 *
 * <p>Mitiga deuda viva #8/#12 del AGENTS.md handoff: sin esta sección, el usuario percibiría
 * el saldo "perdido" (BUY queued con debit aplicado) o la posición "vendida sin acreditar"
 * (SELL queued con decremento aplicado) como un bug.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Orden encolada en Alpaca esperando fill (fuera de horario de mercado)")
public record PendingOrderDto(
        UUID orderId,
        @Schema(description = "Idempotency key generado por el frontend (UUID v4)")
                UUID clientOrderId,
        String ticker,
        OrderSide side,
        int quantity,
        Instant submittedAt,
        @Schema(
                        description =
                                "Total snapshotado al placeOrder (scale=2). Para BUY = lo debitado;"
                                        + " para SELL = lo que se acreditará al fileear. Nullable defensivamente.",
                        example = "705.30")
                String quotedTotal) {}
