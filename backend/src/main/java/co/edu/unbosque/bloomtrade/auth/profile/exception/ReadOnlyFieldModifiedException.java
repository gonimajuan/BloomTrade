package co.edu.unbosque.bloomtrade.auth.profile.exception;

/**
 * El payload de PATCH incluyó un campo read-only (spec HU-F04+F20 §5.3.5). Se lanza desde el
 * handler de {@code HttpMessageNotReadableException} cuando Jackson encuentra una propiedad
 * desconocida con {@code FAIL_ON_UNKNOWN_PROPERTIES=true} (D3). Mapea a 400
 * {@code READ_ONLY_FIELD_MODIFIED} con el nombre del campo intentado.
 */
public class ReadOnlyFieldModifiedException extends RuntimeException {

    private final String fieldName;

    public ReadOnlyFieldModifiedException(String fieldName) {
        super("Campo read-only: " + fieldName);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }
}
