package co.edu.unbosque.bloomtrade.portfolio.service;

import co.edu.unbosque.bloomtrade.portfolio.domain.UserBalance;
import co.edu.unbosque.bloomtrade.portfolio.repository.UserBalanceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Implementación de {@link BalanceInitializer} (componente {@code BalanceInitializer} de
 * ARCHITECTURE.md §4). Sin {@code @Transactional} propio: participa de la transacción del
 * {@code RegisterService} invocador (D4).
 */
@Service
public class DefaultBalanceInitializer implements BalanceInitializer {

    private final UserBalanceRepository userBalanceRepository;

    public DefaultBalanceInitializer(UserBalanceRepository userBalanceRepository) {
        this.userBalanceRepository = userBalanceRepository;
    }

    @Override
    public void initializeBalance(UUID userId) {
        userBalanceRepository.save(UserBalance.initial(userId));
    }
}
