package co.edu.unbosque.bloomtrade.auth.domain;

import co.edu.unbosque.bloomtrade.auth.profile.domain.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Cuenta de inversionista (tabla {@code app.users}, migraciones V2 + V3).
 *
 * <p>Se construye únicamente vía {@link #register} (un inversionista nace {@code INVESTOR} /
 * {@code ACTIVE}, spec HU-F01 §5.1 paso 10). No se usa {@code @Builder}/{@code @AllArgsConstructor}
 * en entidades JPA (CONVENTIONS §5.4.3).
 *
 * <p>HU-F04+F20 (V3) extiende el agregado con {@code notificationChannel} y
 * {@code tickersOfInterest}. La mutación parcial se hace vía {@link #applyProfileUpdate}
 * — único punto de entrada de cambios desde {@code ProfileService} (D19: encapsulación del
 * PATCH parcial dentro del entity).
 */
@Entity
@Table(schema = "app", name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "nombre_completo", nullable = false, length = 100)
    private String nombreCompleto;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_documento", nullable = false, length = 15)
    private DocumentType tipoDocumento;

    @Column(name = "numero_documento", nullable = false, length = 15)
    private String numeroDocumento;

    @Column(name = "telefono", nullable = false, length = 20)
    private String telefono;

    @Enumerated(EnumType.STRING)
    @Column(name = "rol", nullable = false, length = 20)
    private UserRole rol;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private UserStatus estado;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_channel", nullable = false, length = 20)
    private NotificationChannel notificationChannel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tickers_of_interest", nullable = false, columnDefinition = "jsonb")
    private List<String> tickersOfInterest;

    @Column(name = "acepto_terminos_at", nullable = false)
    private Instant aceptoTerminosAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private User(
            String email,
            String passwordHash,
            String nombreCompleto,
            DocumentType tipoDocumento,
            String numeroDocumento,
            String telefono,
            Instant aceptoTerminosAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nombreCompleto = nombreCompleto;
        this.tipoDocumento = tipoDocumento;
        this.numeroDocumento = numeroDocumento;
        this.telefono = telefono;
        this.rol = UserRole.INVESTOR;
        this.estado = UserStatus.ACTIVE;
        this.notificationChannel = NotificationChannel.EMAIL;
        this.tickersOfInterest = new ArrayList<>();
        this.aceptoTerminosAt = aceptoTerminosAt;
    }

    /** Crea un inversionista nuevo ({@code INVESTOR}/{@code ACTIVE}, canal {@code EMAIL}, sin tickers). */
    public static User register(
            String email,
            String passwordHash,
            String nombreCompleto,
            DocumentType tipoDocumento,
            String numeroDocumento,
            String telefono,
            Instant aceptoTerminosAt) {
        return new User(
                email,
                passwordHash,
                nombreCompleto,
                tipoDocumento,
                numeroDocumento,
                telefono,
                aceptoTerminosAt);
    }

    /**
     * Aplica un PATCH parcial al perfil (HU-F04 + HU-F20). Solo los campos no-{@code null} se
     * modifican (semántica PATCH del SPEC §5.1 + D2 del plan).
     *
     * <p>Para limpiar {@code tickersOfInterest} pásese una lista vacía explícitamente; {@code null}
     * significa "no enviar", no "limpiar". La lista se copia defensivamente para que el caller no
     * pueda mutar el estado interno del agregado tras la llamada.
     */
    public void applyProfileUpdate(
            String nombreCompleto,
            String telefono,
            NotificationChannel notificationChannel,
            List<String> tickersOfInterest) {
        if (nombreCompleto != null) {
            this.nombreCompleto = nombreCompleto;
        }
        if (telefono != null) {
            this.telefono = telefono;
        }
        if (notificationChannel != null) {
            this.notificationChannel = notificationChannel;
        }
        if (tickersOfInterest != null) {
            this.tickersOfInterest = new ArrayList<>(tickersOfInterest);
        }
    }

    /** Vista inmutable del listado de tickers — evita exposición mutable del estado interno. */
    public List<String> getTickersOfInterest() {
        return Collections.unmodifiableList(this.tickersOfInterest);
    }
}
