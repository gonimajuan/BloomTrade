package co.edu.unbosque.bloomtrade.trading.exception;

/**
 * Cantidad inválida en una orden (HU-F09 §5.3.2). Casos: {@code quantity ≤ 0}, valor no
 * entero, o mayor que {@code MAX_QUANTITY_PER_ORDER} (D7: hardcoded 10000).
 *
 * <p>Bean Validation suele atajarlo antes ({@code @Min}/{@code @Max} en DTOs); esta excepción
 * cubre validaciones del {@code TradingService} (defensa en profundidad). Mapeada a 400.
 */
public class InvalidQuantityException extends RuntimeException {

    public InvalidQuantityException(String message) {
        super(message);
    }
}
