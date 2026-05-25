package co.edu.unbosque.bloomtrade.trading.history.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Respuesta paginada de {@code GET /api/v1/orders} (HU-F17 §6.3 SPEC).
 */
@Schema(description = "Historial paginado de órdenes del usuario.")
public record OrderHistoryResponse(
        List<OrderHistoryDto> content,
        PaginationDto pagination) {}
