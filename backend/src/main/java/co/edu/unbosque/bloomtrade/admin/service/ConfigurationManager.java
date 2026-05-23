package co.edu.unbosque.bloomtrade.admin.service;

import co.edu.unbosque.bloomtrade.admin.domain.CommissionRate;
import co.edu.unbosque.bloomtrade.admin.repository.CommissionRateRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Capa delgada de configuración runtime (TAC-M2 — diferir el enlace mediante configuración).
 *
 * <p>HU-F09 Lote C: por ahora solo expone {@link #getCommissionPercentage}. HU-F30 (post-MVP)
 * agregará UI admin para mutar las filas de {@code config.commission_rates}.
 *
 * <p>D18: capa delgada, NO cache de Spring. Cada call lee de BD. Indexado por rol;
 * irrelevante el cost para el volumen MVP (single user testing).
 */
@Service
public class ConfigurationManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationManager.class);

    private final CommissionRateRepository commissionRateRepository;
    private final BigDecimal defaultCommissionPct;

    public ConfigurationManager(
            CommissionRateRepository commissionRateRepository,
            @Value("${trading.default-commission-pct:0.02}") BigDecimal defaultCommissionPct) {
        this.commissionRateRepository = commissionRateRepository;
        this.defaultCommissionPct = defaultCommissionPct;
    }

    /**
     * Devuelve la tasa de comisión activa para el rol. Si {@code config.commission_rates} no
     * tiene fila activa para ese rol (fail-safe), retorna el fallback de
     * {@code trading.default-commission-pct} con WARN log.
     *
     * <p>El seed de la migración V5 inserta {@code (INVESTOR, 0.0200)} → el fallback solo se
     * dispararía si alguien borrara la fila manualmente.
     */
    public BigDecimal getCommissionPercentage(String role) {
        return commissionRateRepository
                .findFirstByRoleAndValidToIsNullOrderByValidFromDesc(role)
                .map(CommissionRate::getPercentage)
                .orElseGet(
                        () -> {
                            log.warn(
                                    "No hay fila activa en config.commission_rates para rol={}. "
                                            + "Usando fallback trading.default-commission-pct={}",
                                    role,
                                    defaultCommissionPct);
                            return defaultCommissionPct;
                        });
    }
}
