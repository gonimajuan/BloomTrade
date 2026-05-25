package co.edu.unbosque.bloomtrade.trading.exception;

/**
 * Side inválido en una orden (HU-F09 §5.3.3). Valor fuera del enum
 * {@link co.edu.unbosque.bloomtrade.trading.domain.OrderSide}. Bean Validation suele atajarlo
 * antes; este caminito es defensa en profundidad.
 *
 * <p>HU-F18 Lote E (cleanup deuda viva #16): eliminada la factory {@code sideNotYetImplemented()}
 * que existía como temporary durante HU-F09 hasta que HU-F10 habilitara SELL. Con HU-F10
 * mergeada en PR #7, el path quedó inalcanzable (dead code confirmado por grep).
 */
public class InvalidSideException extends RuntimeException {

    private final String errorCode;

    private InvalidSideException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public static InvalidSideException invalidSide(String received) {
        return new InvalidSideException("Side inválido: " + received, "INVALID_SIDE");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
