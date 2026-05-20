package co.edu.unbosque.bloomtrade.notification.dto;

/** Datos mínimos para el email de código OTP (spec HU-F02/HU-F03 §9.2). */
public record OtpEmailCommand(
        String userId,
        String toEmail,
        String nombreCompleto,
        String otpCode,
        int expiresInMinutes) {}
