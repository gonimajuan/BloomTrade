package co.edu.unbosque.bloomtrade.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.admin.domain.CommissionRate;
import co.edu.unbosque.bloomtrade.admin.repository.CommissionRateRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigurationManagerTest {

    private static final BigDecimal FALLBACK_PCT = new BigDecimal("0.02");

    @Mock private CommissionRateRepository repository;

    @Test
    void getCommissionPercentage_happyPath_returnsActiveRateFromDb() throws Exception {
        CommissionRate active = buildCommissionRate("INVESTOR", new BigDecimal("0.0250"));
        when(repository.findFirstByRoleAndValidToIsNullOrderByValidFromDesc(eq("INVESTOR")))
                .thenReturn(Optional.of(active));
        ConfigurationManager manager = new ConfigurationManager(repository, FALLBACK_PCT);

        BigDecimal pct = manager.getCommissionPercentage("INVESTOR");

        assertThat(pct).isEqualByComparingTo("0.0250");
    }

    @Test
    void getCommissionPercentage_noActiveRowForRole_returnsFallbackFromConfig() {
        when(repository.findFirstByRoleAndValidToIsNullOrderByValidFromDesc(eq("INVESTOR")))
                .thenReturn(Optional.empty());
        ConfigurationManager manager = new ConfigurationManager(repository, FALLBACK_PCT);

        BigDecimal pct = manager.getCommissionPercentage("INVESTOR");

        assertThat(pct).isEqualByComparingTo(FALLBACK_PCT);
    }

    @Test
    void getCommissionPercentage_unknownRole_returnsFallback() {
        when(repository.findFirstByRoleAndValidToIsNullOrderByValidFromDesc(eq("BROKER")))
                .thenReturn(Optional.empty());
        ConfigurationManager manager = new ConfigurationManager(repository, FALLBACK_PCT);

        BigDecimal pct = manager.getCommissionPercentage("BROKER");

        assertThat(pct).isEqualByComparingTo(FALLBACK_PCT);
    }

    /**
     * Helper: {@link CommissionRate} usa constructor protected; setea el campo via reflection
     * para evitar agregar un constructor público solo para tests (que sería deuda de diseño —
     * la entidad es config inmutable desde el código por HU-F30, HU-F09 solo lee).
     */
    private static CommissionRate buildCommissionRate(String role, BigDecimal pct) throws Exception {
        var constructor = CommissionRate.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        CommissionRate rate = constructor.newInstance();
        setField(rate, "role", role);
        setField(rate, "percentage", pct);
        return rate;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
