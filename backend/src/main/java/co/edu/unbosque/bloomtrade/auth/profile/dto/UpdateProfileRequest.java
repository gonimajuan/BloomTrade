package co.edu.unbosque.bloomtrade.auth.profile.dto;

import co.edu.unbosque.bloomtrade.auth.profile.domain.NotificationChannel;
import co.edu.unbosque.bloomtrade.auth.profile.validation.AllowedTicker;
import co.edu.unbosque.bloomtrade.auth.profile.validation.NoDuplicates;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Payload del PATCH parcial de {@code /api/v1/me} (spec HU-F04+F20 §6.1.2). Todos los campos son
 * opcionales: {@code null} significa "no enviar" (D2 del plan), nunca "borrar a null".
 *
 * <p>Para limpiar {@code tickersOfInterest} pásese una lista vacía {@code []} explícitamente.
 *
 * <p>Los códigos {@code VALIDATION_INVALID_NAME}, {@code VALIDATION_INVALID_PHONE},
 * {@code TOO_MANY_TICKERS}, {@code DUPLICATE_TICKERS}, {@code INVALID_TICKER} viven en
 * {@code validation-messages.properties}. El enum {@code NotificationChannel} se valida por
 * Jackson; valor inválido se mapea a {@code VALIDATION_INVALID_CHANNEL} desde el handler global.
 */
public record UpdateProfileRequest(
        @Size(min = 3, max = 100, message = "VALIDATION_INVALID_NAME") String nombreCompleto,
        @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "VALIDATION_INVALID_PHONE")
                String telefono,
        NotificationChannel notificationChannel,
        @Size(max = 25, message = "TOO_MANY_TICKERS") @NoDuplicates
                List<@AllowedTicker String> tickersOfInterest) {}
