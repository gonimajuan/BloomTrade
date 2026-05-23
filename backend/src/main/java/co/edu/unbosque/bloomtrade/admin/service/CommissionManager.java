package co.edu.unbosque.bloomtrade.admin.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

/**
 * Encapsula el cálculo de comisión (HU-F09 ARCH §4 AdminService.CommissionManager).
 * TAC-M3 (encapsular): {@code TradingService} consume {@link #calculate} sin saber del schema
 * {@code config} ni de la lógica de redondeo.
 *
 * <p>D12: {@link BigDecimal} con {@link RoundingMode#HALF_UP} a 2 decimales. Reglas ARCH §9:
 * <pre>
 *   Valor Comisión = Total Transacción × Porcentaje Comisión
 *   Redondeo a 2 cifras decimales (HALF_UP por convención financiera)
 * </pre>
 */
@Service
public class CommissionManager {

    private final ConfigurationManager configurationManager;

    public CommissionManager(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }

    /**
     * Calcula la comisión a aplicar sobre {@code subtotal} para el rol dado.
     * Resultado con scale=2 (centavos USD), HALF_UP.
     *
     * @param role rol del actor que origina la operación (típicamente {@code "INVESTOR"} en MVP)
     * @param subtotal monto bruto de la transacción (precio × cantidad), {@code BigDecimal} con
     *     scale ≥ 0
     * @return comisión con scale=2 HALF_UP. Garantizado ≥ 0.
     */
    public BigDecimal calculate(String role, BigDecimal subtotal) {
        BigDecimal pct = configurationManager.getCommissionPercentage(role);
        return subtotal.multiply(pct).setScale(2, RoundingMode.HALF_UP);
    }
}
