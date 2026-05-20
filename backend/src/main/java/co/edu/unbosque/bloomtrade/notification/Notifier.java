package co.edu.unbosque.bloomtrade.notification;

import co.edu.unbosque.bloomtrade.notification.dto.WelcomeEmailCommand;

/**
 * Puerto del NotificationService. Interfaz conceptual {@code INotification} de ARCHITECTURE.md §5;
 * nombrada sin prefijo {@code I} por CONVENTIONS.md §5.3 (decisión D1 del plan HU-F01).
 */
public interface Notifier {

    /** Envía (asíncronamente) el email de bienvenida; su fallo no revierte el registro. */
    void sendWelcomeEmail(WelcomeEmailCommand command);
}
