package co.edu.unbosque.bloomtrade.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests parametrizados del {@link CommissionManager} con foco en bordes {@link BigDecimal}
 * y {@link java.math.RoundingMode#HALF_UP}.
 */
@ExtendWith(MockitoExtension.class)
class CommissionManagerTest {

    @Mock private ConfigurationManager configurationManager;
    @InjectMocks private CommissionManager commissionManager;

    @ParameterizedTest(name = "subtotal={0} pct={1} → comisión={2}")
    @CsvSource({
        // happy paths con porcentaje INVESTOR 2%
        "'1000.00', '0.02', '20.00'",
        "'1845.00', '0.02', '36.90'",
        "'9999.99', '0.02', '200.00'",
        // HALF_UP: 1000.001 × 0.02 = 20.00002 → setScale(2, HALF_UP) = 20.00
        "'1000.001', '0.02', '20.00'",
        // HALF_UP: 0.01 × 0.02 = 0.0002 → setScale(2, HALF_UP) = 0.00 (debajo de 0.005)
        "'0.01', '0.02', '0.00'",
        // HALF_UP: 0.25 × 0.02 = 0.005 → setScale(2, HALF_UP) = 0.01 (justo en el límite)
        "'0.25', '0.02', '0.01'",
        // Porcentaje 4-decimales: 1234.56 × 0.0250 = 30.864 → setScale(2, HALF_UP) = 30.86
        "'1234.56', '0.0250', '30.86'",
        // Bordes: subtotal exactamente 0 → comisión 0
        "'0.00', '0.02', '0.00'"
    })
    void calculate_parametrizedCasesProduceExpectedCommission(
            String subtotal, String pct, String expectedCommission) {
        when(configurationManager.getCommissionPercentage(eq("INVESTOR")))
                .thenReturn(new BigDecimal(pct));

        BigDecimal commission = commissionManager.calculate("INVESTOR", new BigDecimal(subtotal));

        // Comparar por valor (compareTo) + assert explícito de scale=2 (centavos USD).
        assertThat(commission).isEqualByComparingTo(new BigDecimal(expectedCommission));
        assertThat(commission.scale()).isEqualTo(2);
    }
}
