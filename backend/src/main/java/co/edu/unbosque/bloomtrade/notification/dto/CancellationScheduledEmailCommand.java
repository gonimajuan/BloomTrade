package co.edu.unbosque.bloomtrade.notification.dto;

import java.time.Instant;

/** Command para email "tu suscripción se cancelará el X" (HU-F06 v1.2 §5.2.1). */
public record CancellationScheduledEmailCommand(
        String userId, String toEmail, String nombreCompleto, Instant currentPeriodEnd) {}
