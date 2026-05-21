package co.edu.unbosque.bloomtrade.auth.profile.domain;

/**
 * Canal preferido del usuario para recibir notificaciones (spec HU-F20, CHECK
 * {@code chk_users_notification_channel}).
 *
 * <p>Default {@code EMAIL} al registrarse (migración V3 setea {@code DEFAULT 'EMAIL'} y todos los
 * usuarios pre-existentes lo heredan).
 */
public enum NotificationChannel {
    EMAIL,
    SMS,
    WHATSAPP
}
