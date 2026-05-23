package co.edu.unbosque.bloomtrade.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Tasa de comisión configurable por rol (tabla {@code config.commission_rates}, migración V5).
 * TAC-M2 (diferir el enlace mediante configuración).
 *
 * <p>Una sola fila activa por rol (UNIQUE PARTIAL {@code WHERE valid_to IS NULL}). HU-F30
 * (post-MVP) expondrá UI admin para crear filas nuevas cerrando la previa con {@code valid_to}.
 * HU-F09 solo LEE — no se introducen métodos de mutación en este agregado.
 *
 * <p>HU-F09 D18: {@code ConfigurationManager} es la capa delgada que resuelve la fila activa;
 * {@code CommissionManager} consume el porcentaje y aplica HALF_UP a 2 decimales.
 */
@Entity
@Table(schema = "config", name = "commission_rates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommissionRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "role", nullable = false, length = 20, updatable = false)
    private String role;

    @Column(name = "percentage", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal percentage;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
