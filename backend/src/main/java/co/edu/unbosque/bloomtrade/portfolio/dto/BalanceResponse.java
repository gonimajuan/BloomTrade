package co.edu.unbosque.bloomtrade.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Output de {@code GET /api/v1/portfolio/balance} (HU-F21 §6.3). Saldo disponible del usuario
 * autenticado.
 *
 * <p>{@code balance} es BigDecimal stringificado con scale=2 y {@code RoundingMode.HALF_UP}
 * (plan D10) para consistencia visual con el resto de DTOs financieros del módulo.
 */
@Schema(description = "Saldo disponible del usuario autenticado")
public record BalanceResponse(
        @Schema(description = "Saldo BigDecimal stringificado, scale=2", example = "8345.67")
                String balance,
        @Schema(description = "Código ISO de la moneda. MVP solo USD.", example = "USD")
                String currency,
        @Schema(
                        description =
                                "Instante UTC del último UPDATE sobre la fila app.user_balances."
                                        + " Si no hubo updates desde el bootstrap, coincide con createdAt.")
                Instant lastUpdatedAt) {}
