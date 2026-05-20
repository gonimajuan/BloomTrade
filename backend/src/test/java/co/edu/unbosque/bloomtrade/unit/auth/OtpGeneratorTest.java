package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.session.OtpGenerator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests unitarios del {@link OtpGenerator} (HU-F02 Lote F / T5.2). */
class OtpGeneratorTest {

    private final OtpGenerator generator = new OtpGenerator();

    @Test
    void shouldGenerateExactlySixDigits() {
        for (int i = 0; i < 100; i++) {
            String otp = generator.generate();
            assertThat(otp).hasSize(6).matches("\\d{6}");
        }
    }

    @Test
    void shouldGenerateDifferentValuesAcrossManyCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(generator.generate());
        }
        // Con 1M combinaciones y 200 muestras, esperamos >180 valores únicos casi siempre.
        // Un fallo aquí indicaría un SecureRandom roto, no un flake estadístico.
        assertThat(seen).hasSizeGreaterThan(180);
    }

    @Test
    void shouldMatchIdenticalCodes() {
        assertThat(generator.matches("123456", "123456")).isTrue();
    }

    @Test
    void shouldNotMatchDifferentCodes() {
        assertThat(generator.matches("123456", "654321")).isFalse();
    }

    @Test
    void shouldNotMatchWhenLengthsDiffer() {
        assertThat(generator.matches("12345", "123456")).isFalse();
        assertThat(generator.matches("1234567", "123456")).isFalse();
    }

    @Test
    void shouldReturnFalseForNullInputsInsteadOfNpe() {
        assertThat(generator.matches(null, "123456")).isFalse();
        assertThat(generator.matches("123456", null)).isFalse();
        assertThat(generator.matches(null, null)).isFalse();
    }
}
