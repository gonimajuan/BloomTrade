package co.edu.unbosque.bloomtrade.notification.dto;

/** Datos mínimos para el email de bienvenida (spec HU-F01 §9.2). */
public record WelcomeEmailCommand(String userId, String toEmail, String nombreCompleto) {}
