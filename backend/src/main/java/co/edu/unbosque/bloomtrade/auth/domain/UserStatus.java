package co.edu.unbosque.bloomtrade.auth.domain;

/** Estados de la cuenta (CHECK {@code chk_users_estado}). El registro crea {@code ACTIVE}. */
public enum UserStatus {
    ACTIVE,
    BLOCKED,
    SUSPENDED
}
