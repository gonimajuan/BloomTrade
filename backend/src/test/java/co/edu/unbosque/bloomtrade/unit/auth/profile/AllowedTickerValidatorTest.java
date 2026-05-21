package co.edu.unbosque.bloomtrade.unit.auth.profile;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.profile.catalog.AllowedTickers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Catálogo de 25 tickers (ARCHITECTURE.md §1) — SPEC HU-F04+F20 §11.1 esquema "Validación de tickers". */
class AllowedTickerValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"AAPL", "MSFT", "GOOGL", "TSLA", "BP", "7203", "BHP", "WOW"})
    void shouldAcceptTickerWhenInCatalog(String ticker) {
        assertThat(AllowedTickers.contains(ticker)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "FOO", // inexistente
                "aapl", // minúsculas
                "AAPL ", // espacio trailing
                "", // vacío
                "ABCD" // formato válido pero no en catálogo
            })
    void shouldRejectTickerWhenNotInCatalog(String ticker) {
        assertThat(AllowedTickers.contains(ticker)).isFalse();
    }

    @Test
    void shouldRejectTickerWhenNull() {
        assertThat(AllowedTickers.contains(null)).isFalse();
    }

    @Test
    void shouldExposeExactly25Tickers() {
        assertThat(AllowedTickers.size()).isEqualTo(25);
    }

    @Test
    void shouldGroupTickersBy5MarketsOf5Each() {
        AllowedTickers.byMarket()
                .forEach(
                        (market, list) ->
                                assertThat(list)
                                        .as("mercado %s debe tener exactamente 5 tickers", market)
                                        .hasSize(5));
        assertThat(AllowedTickers.byMarket()).hasSize(5);
    }
}
