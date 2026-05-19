package co.edu.unbosque.bloomtrade.auth.domain;

/** Roles del sistema (ARCHITECTURE.md §11, CHECK {@code chk_users_rol}). */
public enum UserRole {
    INVESTOR,
    BROKER,
    ADMIN,
    LEGAL,
    BOARD
}
