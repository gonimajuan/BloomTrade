package co.edu.unbosque.bloomtrade.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Anti-regresión del stub MVP. Cuando HU-F14 implemente la lógica real, estos tests deben
 * romperse y reescribirse — su rol es señalizar que el stub está activo.
 */
class MarketScheduleManagerTest {

    private final MarketScheduleManager scheduleManager = new MarketScheduleManager();

    @ParameterizedTest
    @ValueSource(strings = {"AAPL", "MSFT", "HSBA", "7203", "BHP"})
    void isOpenNow_anyAllowedTicker_returnsTrueInMvpStub(String ticker) {
        assertThat(scheduleManager.isOpenNow(ticker)).isTrue();
    }

    @Test
    void isOpenNow_unknownTicker_alsoReturnsTrueInMvpStub() {
        // Validación de ticker es responsabilidad de TradingService (vía AllowedTickers).
        // El stub no debe ramificar por ticker — siempre true.
        assertThat(scheduleManager.isOpenNow("UNKNOWN")).isTrue();
    }
}
