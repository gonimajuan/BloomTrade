package co.edu.unbosque.bloomtrade.trading.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Input de {@code POST /api/v1/orders/quote} (HU-F09 §6.1.1). Quote informativo previo a la
 * confirmación de orden — sin persistencia, sin llamadas mutables.
 */
public record QuoteRequest(
        @NotBlank(message = "VALIDATION_REQUIRED") String ticker,
        @NotNull(message = "VALIDATION_REQUIRED") OrderSide side,
        @NotNull(message = "VALIDATION_REQUIRED")
                @Min(value = 1, message = "INVALID_QUANTITY")
                @Max(value = 10_000, message = "INVALID_QUANTITY")
                Integer quantity) {}
