package co.edu.unbosque.bloomtrade.shared.web;

import co.edu.unbosque.bloomtrade.auth.exception.AccountLockedException;
import co.edu.unbosque.bloomtrade.auth.exception.AccountNotActiveException;
import co.edu.unbosque.bloomtrade.auth.exception.EmailAlreadyRegisteredException;
import co.edu.unbosque.bloomtrade.auth.exception.InvalidCredentialsException;
import co.edu.unbosque.bloomtrade.auth.exception.MaxResendsExceededException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaCodeExpiredException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaInvalidCodeException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaSessionInvalidatedException;
import co.edu.unbosque.bloomtrade.auth.exception.RegistrationTechnicalException;
import co.edu.unbosque.bloomtrade.auth.exception.ResendCooldownActiveException;
import co.edu.unbosque.bloomtrade.auth.exception.TempSessionInvalidException;
import co.edu.unbosque.bloomtrade.auth.exception.TokenExpiredException;
import co.edu.unbosque.bloomtrade.auth.exception.TokenInvalidException;
import co.edu.unbosque.bloomtrade.auth.profile.domain.NotificationChannel;
import co.edu.unbosque.bloomtrade.auth.profile.exception.DuplicateTickersException;
import co.edu.unbosque.bloomtrade.auth.profile.exception.InvalidTickerException;
import co.edu.unbosque.bloomtrade.auth.profile.exception.ReadOnlyFieldModifiedException;
import co.edu.unbosque.bloomtrade.auth.profile.exception.TooManyTickersException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Manejador global de excepciones (spec HU-F01 §6, STACK.md §9.3). Es el <strong>único</strong>
 * punto del sistema autorizado a capturar {@link Exception} de forma genérica (CLAUDE.md #11).
 *
 * <p>Para errores de validación: si hay exactamente un campo con error, su código se promueve al
 * {@code error} de primer nivel (satisface los escenarios Gherkin de spec §11 que esperan
 * "código WEAK_PASSWORD / TERMS_NOT_ACCEPTED / ..."); si hay varios, el {@code error} es
 * {@code VALIDATION_FAILED} y el detalle queda en {@code fieldErrors[]} (decisión D14 del plan).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldErrorItem> items = new ArrayList<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(fe -> items.add(toItem(fe.getField(), fe.getDefaultMessage())));
        for (ObjectError ge : ex.getBindingResult().getGlobalErrors()) {
            items.add(toItem(ge.getObjectName(), ge.getDefaultMessage()));
        }
        return validationResponse(items, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldErrorItem> items = new ArrayList<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            items.add(toItem(lastNode(v.getPropertyPath().toString()), v.getMessage()));
        }
        return validationResponse(items, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        // HU-F04+F20 D3: cliente intentó PATCH /me con un campo no editable (email, rol, etc.).
        // Jackson lo detecta porque FAIL_ON_UNKNOWN_PROPERTIES=true (application.yml).
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException upe) {
            String field = upe.getPropertyName();
            String code = "READ_ONLY_FIELD_MODIFIED";
            return ResponseEntity.badRequest()
                    .body(
                            ErrorResponse.validation(
                                    400,
                                    code,
                                    ValidationMessages.humanFor(code),
                                    request.getRequestURI(),
                                    TraceIdFilter.currentTraceId(),
                                    List.of(
                                            new FieldErrorItem(
                                                    field,
                                                    code,
                                                    ValidationMessages.humanFor(code)))));
        }
        // HU-F04+F20: payload con un enum (NotificationChannel) en valor desconocido.
        if (cause instanceof InvalidFormatException ife
                && ife.getTargetType() != null
                && NotificationChannel.class.isAssignableFrom(ife.getTargetType())) {
            String code = "VALIDATION_INVALID_CHANNEL";
            return ResponseEntity.badRequest()
                    .body(
                            ErrorResponse.of(
                                    400,
                                    code,
                                    ValidationMessages.humanFor(code),
                                    request.getRequestURI(),
                                    TraceIdFilter.currentTraceId()));
        }
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.of(
                                400,
                                VALIDATION_FAILED,
                                ValidationMessages.humanFor(VALIDATION_FAILED),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(ReadOnlyFieldModifiedException.class)
    public ResponseEntity<ErrorResponse> handleReadOnlyField(
            ReadOnlyFieldModifiedException ex, HttpServletRequest request) {
        String code = "READ_ONLY_FIELD_MODIFIED";
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.validation(
                                400,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId(),
                                List.of(
                                        new FieldErrorItem(
                                                ex.getFieldName(),
                                                code,
                                                ValidationMessages.humanFor(code)))));
    }

    @ExceptionHandler(InvalidTickerException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTicker(
            InvalidTickerException ex, HttpServletRequest request) {
        String code = "INVALID_TICKER";
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.of(
                                400,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(TooManyTickersException.class)
    public ResponseEntity<ErrorResponse> handleTooManyTickers(
            TooManyTickersException ex, HttpServletRequest request) {
        String code = "TOO_MANY_TICKERS";
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.of(
                                400,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(DuplicateTickersException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateTickers(
            DuplicateTickersException ex, HttpServletRequest request) {
        String code = "DUPLICATE_TICKERS";
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.of(
                                400,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailDuplicate(
            EmailAlreadyRegisteredException ex, HttpServletRequest request) {
        String code = "EMAIL_ALREADY_REGISTERED";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        ErrorResponse.of(
                                409,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(RegistrationTechnicalException.class)
    public ResponseEntity<ErrorResponse> handleRegistrationTechnical(
            RegistrationTechnicalException ex, HttpServletRequest request) {
        log.error("Error técnico durante el registro", ex);
        return internalError(request);
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTokenExpired(
            TokenExpiredException ex, HttpServletRequest request) {
        return authError(request, "TOKEN_EXPIRED");
    }

    @ExceptionHandler(TokenInvalidException.class)
    public ResponseEntity<ErrorResponse> handleTokenInvalid(
            TokenInvalidException ex, HttpServletRequest request) {
        return authError(request, "TOKEN_INVALID");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(
            InvalidCredentialsException ex, HttpServletRequest request) {
        return authError(request, "INVALID_CREDENTIALS");
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<ErrorResponse> handleAccountLocked(
            AccountLockedException ex, HttpServletRequest request) {
        long seconds = ex.getSecondsRemaining();
        long minutes = Math.max(1L, (seconds + 59) / 60);
        String message =
                String.format(
                        "Cuenta bloqueada temporalmente por demasiados intentos fallidos. "
                                + "Intenta de nuevo en %d minuto(s).",
                        minutes);
        return ResponseEntity.status(HttpStatus.LOCKED)
                .header("Retry-After", String.valueOf(seconds))
                .body(
                        ErrorResponse.of(
                                423,
                                "ACCOUNT_LOCKED",
                                message,
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotActive(
            AccountNotActiveException ex, HttpServletRequest request) {
        String code = "ACCOUNT_NOT_ACTIVE";
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(
                        ErrorResponse.of(
                                403,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(TempSessionInvalidException.class)
    public ResponseEntity<ErrorResponse> handleTempSessionInvalid(
            TempSessionInvalidException ex, HttpServletRequest request) {
        return authError(request, "TEMP_SESSION_INVALID");
    }

    @ExceptionHandler(MfaInvalidCodeException.class)
    public ResponseEntity<ErrorResponse> handleMfaInvalidCode(
            MfaInvalidCodeException ex, HttpServletRequest request) {
        String message =
                String.format(
                        "Código incorrecto. Intentos restantes: %d.",
                        ex.getAttemptsRemaining());
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.of(
                                400,
                                "MFA_INVALID_CODE",
                                message,
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(MfaCodeExpiredException.class)
    public ResponseEntity<ErrorResponse> handleMfaCodeExpired(
            MfaCodeExpiredException ex, HttpServletRequest request) {
        String code = "MFA_CODE_EXPIRED";
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.of(
                                400,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(MfaSessionInvalidatedException.class)
    public ResponseEntity<ErrorResponse> handleMfaSessionInvalidated(
            MfaSessionInvalidatedException ex, HttpServletRequest request) {
        String code = "MFA_SESSION_INVALIDATED";
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(
                        ErrorResponse.of(
                                403,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(ResendCooldownActiveException.class)
    public ResponseEntity<ErrorResponse> handleResendCooldown(
            ResendCooldownActiveException ex, HttpServletRequest request) {
        long seconds = ex.getSecondsRemaining();
        String message =
                String.format("Espera %d segundo(s) antes de solicitar otro código.", seconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(seconds))
                .body(
                        ErrorResponse.of(
                                429,
                                "RESEND_COOLDOWN_ACTIVE",
                                message,
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(MaxResendsExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxResendsExceeded(
            MaxResendsExceededException ex, HttpServletRequest request) {
        String code = "MAX_RESENDS_EXCEEDED";
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(
                        ErrorResponse.of(
                                429,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Error inesperado no controlado", ex);
        return internalError(request);
    }

    private ResponseEntity<ErrorResponse> validationResponse(
            List<FieldErrorItem> items, HttpServletRequest request) {
        String topCode = items.size() == 1 ? items.get(0).code() : VALIDATION_FAILED;
        return ResponseEntity.badRequest()
                .body(
                        ErrorResponse.validation(
                                400,
                                topCode,
                                ValidationMessages.humanFor(topCode),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId(),
                                items));
    }

    private ResponseEntity<ErrorResponse> internalError(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        ErrorResponse.of(
                                500,
                                INTERNAL_ERROR,
                                ValidationMessages.humanFor(INTERNAL_ERROR),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    private ResponseEntity<ErrorResponse> authError(HttpServletRequest request, String code) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(
                        ErrorResponse.of(
                                401,
                                code,
                                ValidationMessages.humanFor(code),
                                request.getRequestURI(),
                                TraceIdFilter.currentTraceId()));
    }

    private FieldErrorItem toItem(String field, String code) {
        String resolved = code != null ? code : VALIDATION_FAILED;
        return new FieldErrorItem(field, resolved, ValidationMessages.humanFor(resolved));
    }

    private String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot >= 0 ? propertyPath.substring(dot + 1) : propertyPath;
    }
}
