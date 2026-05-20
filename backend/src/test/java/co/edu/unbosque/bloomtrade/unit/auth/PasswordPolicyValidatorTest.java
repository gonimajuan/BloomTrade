package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.validation.PasswordPolicyValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Política de password encapsulada (TAC-M3) — matriz Gherkin spec HU-F01 §11. */
class PasswordPolicyValidatorTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Short1", // < 10 chars
                "alllowercase123", // sin mayúscula
                "NOLOWERCASE123", // sin minúscula
                "NoNumbersHere", // sin dígito
                "Abcdefgh1" // 9 chars (límite inferior)
            })
    void shouldRejectPasswordWhenPolicyNotMet(String weak) {
        assertThat(PasswordPolicyValidator.isStrong(weak)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ValidPass123", // caso feliz spec §11
                "Abcdefgh12" // 10 chars exactos (límite inferior válido)
            })
    void shouldAcceptPasswordWhenPolicyMet(String strong) {
        assertThat(PasswordPolicyValidator.isStrong(strong)).isTrue();
    }

    @Test
    void shouldRejectPasswordWhenNull() {
        assertThat(PasswordPolicyValidator.isStrong(null)).isFalse();
    }

    @Test
    void shouldRejectPasswordWhenExceedsMaxLength() {
        String tooLong = "Aa1" + "x".repeat(98); // 101 chars

        assertThat(PasswordPolicyValidator.isStrong(tooLong)).isFalse();
    }
}
