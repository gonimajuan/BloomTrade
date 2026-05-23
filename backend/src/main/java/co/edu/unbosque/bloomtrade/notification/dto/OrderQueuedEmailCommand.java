package co.edu.unbosque.bloomtrade.notification.dto;

import java.math.BigDecimal;

/**
 * Command para el email "Tu orden de compra quedó en cola" (HU-F09 D29 emergente Lote H.5).
 * El backend debitó el cash reservado pero la posición todavía no existe — el correo lo
 * comunica de forma explícita para que el usuario sepa que su saldo se redujo a la espera
 * del fill cuando abra el mercado.
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
        String alpacaOrderId) {}
