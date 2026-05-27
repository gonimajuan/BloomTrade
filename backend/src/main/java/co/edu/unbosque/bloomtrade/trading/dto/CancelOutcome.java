package co.edu.unbosque.bloomtrade.trading.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Resultado del polling de cancelación de Alpaca (HU-F15 D24 D-NO-NEW-EXCEPTION-FOR-RACE-FILLED).
 *
 * <p>Sealed type que modela las 3 transiciones posibles tras {@code DELETE /v2/orders/{id}} +
 * polling de 2s sobre {@code GET /v2/orders/{id}}:
 * <ul>
 *   <li>{@link Canceled} — Alpaca confirmó {@code status=canceled} dentro del timeout. Happy path
 *       síncrono: refund/restore se aplica en el mismo tx del cancel.</li>
 *   <li>{@link PendingCancel} — Polling agotado sin estado terminal. La orden queda
 *       {@code PENDING + cancel_requested_at}; reconcile lazy v2 la materializa después.</li>
 *   <li>{@link RaceFilled} — Race condition: Alpaca confirmó {@code status=filled} durante el
 *       polling (la orden se ejecutó justo antes de que llegara el cancel). Se trata como
 *       {@code EXECUTED} (D17 D-RACE-FILLED-UX), no como cancelación.</li>
 * </ul>
 *
 * <p>NO modelado como excepción porque RACE_FILLED es happy-path alternativo, no error
 * (lanzar excepción en flujo de éxito haría rollback de la transacción).
 */
public sealed interface CancelOutcome
        permits CancelOutcome.Canceled,
                CancelOutcome.PendingCancel,
                CancelOutcome.RaceFilled {

    /** Alpaca confirmó {@code canceled} en el polling. {@code alpacaCanceledAt} viene del response. */
    record Canceled(Instant alpacaCanceledAt) implements CancelOutcome {}

    /** Polling agotó el timeout sin estado terminal. La razón es informativa para audit. */
    record PendingCancel(String reason) implements CancelOutcome {}

    /**
     * Alpaca confirmó {@code filled} durante el polling — la orden se ejecutó antes de que
     * llegara la cancelación. Se aplica como fill late-arrival (D17 D-RACE-FILLED-UX).
     */
    record RaceFilled(BigDecimal filledAvgPrice, int filledQty, Instant alpacaFilledAt)
            implements CancelOutcome {}
}
