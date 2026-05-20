package co.edu.unbosque.bloomtrade.auth.exception;

/**
 * El OTP {@code otp:{tempSessionId}} no existe en Redis (TTL agotado o reemplazado por un resend
 * antes de que llegara el código viejo, spec HU-F02 §5.3.6). Mapea a 400 {@code MFA_CODE_EXPIRED}.
 */
public class MfaCodeExpiredException extends RuntimeException {

    public MfaCodeExpiredException() {
        super("OTP expirado");
    }
}
