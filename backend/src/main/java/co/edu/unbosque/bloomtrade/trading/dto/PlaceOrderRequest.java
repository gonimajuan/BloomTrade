package co.edu.unbosque.bloomtrade.trading.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import co.edu.unbosque.bloomtrade.trading.domain.OrderType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Input de {@code POST /api/v1/orders} (HU-F09 §6.1.2). Crear y ejecutar una orden Market.
 * Idempotente vía {@link #clientOrderId} — la misma UUID enviada dos veces produce 1 orden.
 */
public record PlaceOrderRequest(
        @NotNull(message = "VALIDATION_REQUIRED") UUID clientOrderId,
        @NotBlank(message = "VALIDATION_REQUIRED") String ticker,
        @NotNull(message = "VALIDATION_REQUIRED") OrderSide side,
        @NotNull(message = "VALIDATION_REQUIRED") OrderType type,
        @NotNull(message = "VALIDATION_REQUIRED")
                @Min(value = 1, message = "INVALID_QUANTITY")
                @Max(value = 10_000, message = "INVALID_QUANTITY")
                Integer quantity) {}
