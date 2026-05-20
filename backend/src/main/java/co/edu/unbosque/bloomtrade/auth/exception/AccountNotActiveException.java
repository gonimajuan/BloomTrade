package co.edu.unbosque.bloomtrade.auth.exception;

import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;

/**
 * Usuario existe pero su {@code estado} no es {@code ACTIVE} ({@code BLOCKED} o {@code SUSPENDED},
 * spec HU-F02 §5.3.4). Mapea a 403 {@code ACCOUNT_NOT_ACTIVE} con mensaje genérico (no se revela
 * el estado exacto al cliente; el estado sí se registra en el evento de auditoría).
 */
public class AccountNotActiveException extends RuntimeException {

    private final transient UserStatus accountStatus;

    public AccountNotActiveException(UserStatus accountStatus) {
        super("Cuenta no activa");
        this.accountStatus = accountStatus;
    }

    public UserStatus getAccountStatus() {
        return accountStatus;
    }
}
