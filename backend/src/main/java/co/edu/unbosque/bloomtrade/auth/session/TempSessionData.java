package co.edu.unbosque.bloomtrade.auth.session;

import java.time.Instant;

/**
 * Estado serializable de la sesión temporal entre {@code /login} y {@code /mfa/verify}
 * (spec HU-F02 §5.1 paso 12). Se persiste como JSON en Redis bajo {@code temp-session:{id}} con
 * TTL de 5 min — el {@code role} se almacena como {@code String} para no acoplar el JSON al enum
 * Java (TAC-M3 — Encapsular).
 */
public record TempSessionData(String userId, String email, String role, Instant createdAt) {}
