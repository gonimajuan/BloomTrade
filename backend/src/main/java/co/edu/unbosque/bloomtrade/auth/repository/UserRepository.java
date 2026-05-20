package co.edu.unbosque.bloomtrade.auth.repository;

import co.edu.unbosque.bloomtrade.auth.domain.User;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso a {@code app.users}. */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Existencia case-insensitive del email (spec HU-F01 §5.1 paso 8). Spring Data genera
     * {@code lower(email) = lower(?)}, alineado con el índice único {@code idx_users_email_lower}.
     */
    boolean existsByEmailIgnoreCase(String email);
}
