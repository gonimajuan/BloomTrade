package co.edu.unbosque.bloomtrade.notification.dto;

import java.math.BigDecimal;

/**
 * Command para el email "Tu orden de compra/venta quedó en cola" (HU-F09 D29 emergente Lote H.5,
 * extendido HU-F10 D9 Lote C).
 *
 * <p><b>BUY queued</b>: el backend debitó el cash reservado pero la posición todavía no existe
 * — el correo lo comunica de forma explícita para que el usuario sepa que su saldo se redujo a
 * la espera del fill cuando abra el mercado. {@code positionResultingQty} es {@code null}.
 *
 * <p><b>SELL queued</b> (HU-F10 D9 D-SELL-QUEUED-RISK): la posición YA se decrementó
 * optimistamente; el balance NO se acreditó todavía (precio de fill desconocido). El correo
 * comunica "recibirás el crédito al ejecutarse". {@code positionResultingQty} refleja la
 * cantidad post-decrement (0 si se borró por liquidación total).
 */
public record OrderQueuedEmailCommand(
        String userId,
        String toEmail,
        String nombreCompleto,
        String ticker,
        int quantity,
        BigDecimal quotedUnitPrice,
        BigDecimal quotedTotal,
        BigDecimal commission,
        BigDecimal newBalance,
        String alpacaOrderId,
        Integer positionResultingQty) {}
