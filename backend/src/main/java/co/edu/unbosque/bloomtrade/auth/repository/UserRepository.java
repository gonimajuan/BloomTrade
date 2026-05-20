package co.edu.unbosque.bloomtrade.auth.repository;

import co.edu.unbosque.bloomtrade.auth.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso a {@code app.users}. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Existencia case-insensitive del email (spec HU-F01 §5.1 paso 8). Spring Data genera
     * {@code lower(email) = lower(?)}, alineado con el índice único {@code idx_users_email_lower}.
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Búsqueda case-insensitive del usuario por email (spec HU-F02 §5.1 paso 7, decisión D10 del
     * plan). Mismo índice funcional {@code idx_users_email_lower} de la migración V2 — la consulta
     * cuesta lo mismo que un lookup por PK.
     */
    Optional<User> findByEmailIgnoreCase(String email);
}
