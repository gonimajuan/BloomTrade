package co.edu.unbosque.bloomtrade.unit.auth.profile;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.profile.validation.NoDuplicatesValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validador genérico de unicidad en colecciones — SPEC HU-F04+F20 §11.1 esquema "DUPLICATE_TICKERS". */
class NoDuplicatesValidatorTest {

    @Test
    void shouldAcceptCollectionWhenAllElementsUnique() {
        assertThat(NoDuplicatesValidator.hasNoDuplicates(List.of("AAPL", "MSFT", "GOOGL")))
                .isTrue();
    }

    @Test
    void shouldRejectCollectionWhenSimpleDuplicate() {
        assertThat(NoDuplicatesValidator.hasNoDuplicates(List.of("AAPL", "AAPL"))).isFalse();
    }

    @Test
    void shouldRejectCollectionWhenMultipleDuplicates() {
        assertThat(NoDuplicatesValidator.hasNoDuplicates(List.of("AAPL", "AAPL", "AAPL")))
                .isFalse();
    }

    @Test
    void shouldAcceptCollectionWhenEmpty() {
        assertThat(NoDuplicatesValidator.hasNoDuplicates(List.of())).isTrue();
    }

    @Test
    void shouldAcceptCollectionWhenNull() {
        assertThat(NoDuplicatesValidator.hasNoDuplicates(null)).isTrue();
    }
}
