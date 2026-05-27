package co.edu.unbosque.bloomtrade.notification.dto;

import java.math.BigDecimal;

/**
 * Command para el email "Tu orden fue cancelada / expiró sin ejecutarse" (HU-F15 Lote B).
 *
 * <p>Side-mutually-exclusive entre {@code refundedAmount} (BUY) y {@code restoredQty} (SELL):
 * <ul>
 *   <li>BUY: {@code refundedAmount = quotedTotal} (lo que se restaurará al balance),
 *       {@code restoredQty = null}.</li>
 *   <li>SELL: {@code refundedAmount = null}, {@code restoredQty = cantidad restaurada a la posición}.</li>
 * </ul>
 *
 * <p>{@code isExpired} controla el copy del template (D15 D-EMAIL-EXPIRED-REUSE): {@code true}
 * para EXPIRED, {@code false} para CANCELED. Los 2 templates {@code order-canceled-buy.html} y
 * {@code order-canceled-sell.html} se reusan con esta sola diferencia.
 *
 * <p>{@code newBalance} es el balance post-refund (BUY) o el balance actual sin cambios (SELL —
 * la cancelación no toca balance). Útil para mostrar "Tu saldo actual: USD X" en el email.
 */
public record OrderCanceledEmailCommand(
        String userId,
        String toEmail,
        String nombreCompleto,
        String ticker,
        int quantity,
        BigDecimal refundedAmount,
        Integer restoredQty,
        BigDecimal newBalance,
        boolean isExpired) {}
