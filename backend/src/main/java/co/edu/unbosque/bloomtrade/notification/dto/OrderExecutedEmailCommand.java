package co.edu.unbosque.bloomtrade.notification.dto;

import java.math.BigDecimal;

/**
 * Command para el email "Tu orden de compra fue ejecutada" (HU-F09 Lote F). Montos como
 * {@link BigDecimal} para preservar precisión hasta el render (el template los formatea como
 * {@code toPlainString()}).
 */
public record OrderExecutedEmailCommand(
        String userId,
        String toEmail,
        String nombreCompleto,
        String ticker,
        int quantity,
        BigDecimal executionUnitPrice,
        BigDecimal executionTotal,
        BigDecimal commission,
        BigDecimal newBalance) {}
