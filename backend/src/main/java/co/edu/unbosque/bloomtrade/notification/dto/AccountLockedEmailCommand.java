package co.edu.unbosque.bloomtrade.notification.dto;

/** Datos mínimos para el email de bloqueo temporal de cuenta (spec HU-F02 §5.3.2). */
public record AccountLockedEmailCommand(
        String userId, String toEmail, String nombreCompleto, int lockDurationMinutes) {}
