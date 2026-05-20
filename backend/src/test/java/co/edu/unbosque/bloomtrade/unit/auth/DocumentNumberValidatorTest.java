package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.validation.DocumentNumberValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** numeroDocumento según tipoDocumento — matriz Gherkin spec HU-F01 §11. */
class DocumentNumberValidatorTest {

    @ParameterizedTest
    @CsvSource({
        "CC,        abc123,     false", // no numérico
        "CC,        12345,      false", // 5 dígitos (< 6)
        "CC,        1234567890, true", // 10 dígitos
        "CC,        123456,     true", // límite inferior 6
        "CC,        1234567890123, false", // 13 dígitos (> 12)
        "CE,        123456,     true",
        "CE,        12345,      false",
        "PASAPORTE, AB123456,   true", // alfanumérico 8
        "PASAPORTE, AB,         false", // 2 chars (< 6)
        "PASAPORTE, ABCDEF,     true", // límite inferior 6
        "PASAPORTE, ABCDEFGHIJKLMNOP, false" // 16 chars (> 15)
    })
    void shouldValidateDocumentNumberByType(DocumentType tipo, String numero, boolean expected) {
        assertThat(DocumentNumberValidator.matches(tipo, numero)).isEqualTo(expected);
    }

    @Test
    void shouldDeferWhenTypeOrNumberIncomplete() {
        // Casos incompletos los gobiernan @NotNull/@NotBlank: el validador no opina.
        assertThat(DocumentNumberValidator.matches(null, "1234567890")).isTrue();
        assertThat(DocumentNumberValidator.matches(DocumentType.CC, null)).isTrue();
        assertThat(DocumentNumberValidator.matches(DocumentType.CC, "   ")).isTrue();
    }
}
