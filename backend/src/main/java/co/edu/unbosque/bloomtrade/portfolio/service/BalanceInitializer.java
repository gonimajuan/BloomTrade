package co.edu.unbosque.bloomtrade.portfolio.service;

import java.util.UUID;

/**
 * Puerto que expone PortfolioService para crear el balance inicial de un usuario recién
 * registrado. Interfaz conceptual {@code IBalanceInitializer} de ARCHITECTURE.md §5; nombrada sin
 * prefijo {@code I} por CONVENTIONS.md §5.3 (decisión D1 del plan HU-F01).
 *
 * <p>Lo consume {@code AuthService.RegisterService} <strong>dentro de su misma transacción</strong>
 * (decisión D4; spec §14 Resolved; ARCHITECTURE.md changelog 2.1). No abre transacción propia: se
 * une a la del invocador para que User + balance sean atómicos.
 */
public interface BalanceInitializer {

    /** Crea el balance inicial (USD 10,000.00) del usuario indicado. */
    void initializeBalance(UUID userId);
}
