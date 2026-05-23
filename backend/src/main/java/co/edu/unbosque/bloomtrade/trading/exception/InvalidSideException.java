package co.edu.unbosque.bloomtrade.trading.exception;

/**
 * Side inválido en una orden (HU-F09 §5.3.3). Dos casos:
 * <ul>
 *   <li>{@link #sideNotYetImplemented} — {@code side=SELL} mientras HU-F10 no se haya implementado
 *       (Día 7 ROADMAP). El endpoint lo acepta pero el handler responde 400
 *       {@code SIDE_NOT_YET_IMPLEMENTED}.</li>
 *   <li>{@link #invalidSide} — valor en general fuera del enum {@link
 *       co.edu.unbosque.bloomtrade.trading.domain.OrderSide}. Bean Validation suele atajarlo.</li>
 * </ul>
 */
public class InvalidSideException extends RuntimeException {

    private final String errorCode;

    private InvalidSideException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public static InvalidSideException sideNotYetImplemented() {
        return new InvalidSideException(
                "La venta (SELL) estará disponible en HU-F10", "SIDE_NOT_YET_IMPLEMENTED");
    }

    public static InvalidSideException invalidSide(String received) {
        return new InvalidSideException("Side inválido: " + received, "INVALID_SIDE");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
