package co.edu.unbosque.bloomtrade.trading.dto;

import co.edu.unbosque.bloomtrade.trading.domain.OrderSide;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Output de {@code POST /api/v1/orders/quote} (HU-F09 §6.1.1, extendido HU-F10 §6.2.1).
 *
 * <p>Todos los montos van como {@link String} (no {@code BigDecimal}) para preservar precisión
 * en el JSON serializado: Jackson convierte {@code BigDecimal} a {@code number} y puede sufrir
 * pérdida en el lado cliente (JS number = double). El frontend formatea con la string tal cual.
 *
 * <p><b>HU-F10 — semántica side-aware de {@code estimatedTotal}</b>:
 * <ul>
 *   <li><b>BUY</b>: {@code estimatedTotal = subtotal + commission} — lo que se DESCONTARÁ del saldo.</li>
 *   <li><b>SELL</b>: {@code estimatedTotal = subtotal − commission} — lo que se ACREDITARÁ al saldo
 *       (producto neto).</li>
 * </ul>
 * Misma key del JSON, semántica controlada por {@code side}. Frontend hace render side-aware.
 *
 * <p>Los campos {@code sufficientShares} y {@code userShares} son nuevos en HU-F10; siempre
 * están presentes pero solo son significativos cuando {@code side=SELL}. Para BUY:
 * {@code sufficientShares=true} (no aplica) y {@code userShares=0} (no aplica).
 */
public record QuoteResponse(
        String ticker,
        OrderSide side,
        int quantity,
        String estimatedUnitPrice,
        String estimatedSubtotal,
        String commission,
        @Schema(
                description =
                        "Side-aware: BUY = subtotal + commission (descontado al saldo); "
                                + "SELL = subtotal − commission (acreditado al saldo, producto neto).")
                String estimatedTotal,
        String currency,
        String userBalance,
        boolean sufficientFunds,
        @Schema(
                description =
                        "HU-F10. Solo significativo para SELL: true si app.positions.quantity "
                                + "para (userId, ticker) >= quantity solicitada. Para BUY siempre true.")
                boolean sufficientShares,
        @Schema(
                description =
                        "HU-F10. Cantidad actual del usuario en el ticker. Para BUY: 0 si no tiene "
                                + "posición. Para SELL: cantidad disponible para validar contra quantity.")
                int userShares,
        boolean marketOpen,
        Instant quotedAt) {}
