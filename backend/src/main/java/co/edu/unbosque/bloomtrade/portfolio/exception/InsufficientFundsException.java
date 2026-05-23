package co.edu.unbosque.bloomtrade.portfolio.exception;

import java.math.BigDecimal;

/**
 * Saldo insuficiente para completar un débito (HU-F09 SPEC §5.3.4).
 *
 * <p>El {@code GlobalExceptionHandler} mapea esta a HTTP 409 {@code INSUFFICIENT_FUNDS} con
 * details {@code { balance, required, shortfall }}. Se lanza desde {@code PortfolioService#debit}
 * cuando el saldo actual del usuario es menor que el monto requerido — invariante de
 * dominio antes de tocar el agregado {@code UserBalance}.
 *
 * <p>El CHECK constraint {@code chk_balance_nonneg} de V2 es defensa en profundidad: si por
 * race condition se intentara persistir un balance negativo, la BD también lo rechazaría.
 */
public class InsufficientFundsException extends RuntimeException {

    private final BigDecimal balance;
    private final BigDecimal required;

    public InsufficientFundsException(BigDecimal balance, BigDecimal required) {
        super("Saldo insuficiente: balance=" + balance + " required=" + required);
        this.balance = balance;
        this.required = required;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getRequired() {
        return required;
    }

    public BigDecimal getShortfall() {
        return required.subtract(balance);
    }
}
