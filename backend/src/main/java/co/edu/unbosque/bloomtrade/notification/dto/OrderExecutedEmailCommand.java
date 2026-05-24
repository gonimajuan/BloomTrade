package co.edu.unbosque.bloomtrade.notification.dto;

import java.math.BigDecimal;

/**
 * Command para el email "Tu orden de compra/venta fue ejecutada" (HU-F09 Lote F, extendido
 * HU-F10 Lote C). Montos como {@link BigDecimal} para preservar precisión hasta el render
 * (el template los formatea como {@code toPlainString()}).
 *
 * <p>HU-F10: {@code positionResultingQty} y {@code positionDeleted} son nullable; solo son
 * significativos para SELL (cantidad de la posición tras la mutación + flag si la fila fue
 * borrada por liquidación total). Para BUY se llenan con {@code qty} post-upsert + {@code false}
 * — el template BUY los ignora.
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
        BigDecimal newBalance,
        Integer positionResultingQty,
        Boolean positionDeleted) {}
