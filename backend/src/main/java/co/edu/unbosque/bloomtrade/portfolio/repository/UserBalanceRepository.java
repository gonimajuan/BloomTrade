package co.edu.unbosque.bloomtrade.portfolio.repository;

import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso a {@code app.user_balances}. */
public interface UserBalanceRepository extends JpaRepository<UserBalance, UUID> {}
