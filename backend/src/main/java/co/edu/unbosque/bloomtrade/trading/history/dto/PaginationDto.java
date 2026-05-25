package co.edu.unbosque.bloomtrade.trading.history.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Metadata de paginación para listados (HU-F17 §6.3 SPEC). Mirror de Spring Data
 * {@link org.springframework.data.domain.Page} sin acoplar el cliente a Spring Data.
 */
@Schema(description = "Metadata de paginación.")
public record PaginationDto(
        @Schema(description = "Página actual (0-indexed).", example = "0") int page,
        @Schema(description = "Tamaño de página.", example = "10") int size,
        @Schema(description = "Total de elementos en todas las páginas.", example = "47")
                long totalElements,
        @Schema(description = "Total de páginas.", example = "5") int totalPages) {}
