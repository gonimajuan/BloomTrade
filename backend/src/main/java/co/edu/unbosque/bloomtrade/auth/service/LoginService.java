package co.edu.unbosque.bloomtrade.auth.service;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.domain.UserStatus;
import co.edu.unbosque.bloomtrade.auth.dto.LoginRequest;
import co.edu.unbosque.bloomtrade.auth.dto.LoginResponse;
import co.edu.unbosque.bloomtrade.auth.exception.AccountLockedException;
import co.edu.unbosque.bloomtrade.auth.exception.AccountNotActiveException;
import co.edu.unbosque.bloomtrade.auth.exception.InvalidCredentialsException;
import co.edu.unbosque.bloomtrade.auth.ratelimit.LoginAttemptTracker;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.session.TempSessionData;
import co.edu.unbosque.bloomtrade.auth.session.TempSessionManager;
import co.edu.unbosque.bloomtrade.auth.session.OtpGenerator;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.AccountLockedEmailCommand;
import co.edu.unbosque.bloomtrade.notification.dto.OtpEmailCommand;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquesta el paso 1 del flujo de autenticación (spec HU-F02 §5.1 pasos 1-16).
 *
 * <p>Orden de chequeos: lookup → estado ACTIVE → lockout activo → password. Cada DENIED emite
 * {@code LOGIN_ATTEMPT} a auditoría con su {@code reason} específico. El tercer fallo consecutivo
 * dispara {@code lock()} + email de bloqueo + audit {@code ACCOUNT_LOCKED} antes de relanzar la
 * excepción genérica (el atacante no debe enterarse de que justo se bloqueó la cuenta — spec §5.3.2).
 *
 * <p>El email del OTP se dispara vía {@link Notifier#sendOtpEmail} (asíncrono, mismo patrón que el
 * email de bienvenida de HU-F01); si SMTP falla no se revierte la sesión temporal — el usuario
 * puede solicitar reenvío.
 */
@Service
public class LoginService {

    private static final String RESOURCE = "/api/v1/auth/login";
    private static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final int OTP_TTL_MINUTES = 5;
    private static final int TEMP_SESSION_TTL_SECONDS = 300;
    private static final int LOCK_DURATION_SECONDS = 900;
    private static final int LOCK_DURATION_MINUTES = 15;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptTracker loginAttemptTracker;
    private final TempSessionManager tempSessionManager;
    private final OtpGenerator otpGenerator;
    private final Notifier notifier;
    private final Auditor auditor;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            LoginAttemptTracker loginAttemptTracker,
            TempSessionManager tempSessionManager,
            OtpGenerator otpGenerator,
            Notifier notifier,
            Auditor auditor) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptTracker = loginAttemptTracker;
        this.tempSessionManager = tempSessionManager;
        this.otpGenerator = otpGenerator;
        this.notifier = notifier;
        this.auditor = auditor;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request, String ipOrigin) {
        Optional<User> userOpt;
        try {
            userOpt = userRepository.findByEmailIgnoreCase(request.email());
        } catch (DataAccessException e) {
            auditTechnical(request.email(), ipOrigin, e);
            throw e;
        }

        if (userOpt.isEmpty()) {
            // Anti-enumeration (spec §5.3.1): no se incrementa contador para emails inexistentes.
            auditDenied(
                    request.email(),
                    null,
                    null,
                    ipOrigin,
                    "INVALID_CREDENTIALS",
                    null);
            throw new InvalidCredentialsException();
        }

        User user = userOpt.get();
        UUID userId = user.getId();

        if (user.getEstado() != UserStatus.ACTIVE) {
            auditDenied(
                    request.email(),
                    userId,
                    user.getRol().name(),
                    ipOrigin,
                    "ACCOUNT_NOT_ACTIVE",
                    builder ->
                            builder.detail("accountStatus", user.getEstado().name()));
            throw new AccountNotActiveException(user.getEstado());
        }

        if (loginAttemptTracker.isLocked(userId)) {
            long remaining = loginAttemptTracker.lockoutSecondsRemaining(userId);
            auditDenied(
                    request.email(),
                    userId,
                    user.getRol().name(),
                    ipOrigin,
                    "ACCOUNT_LOCKED",
                    builder -> builder.detail("lockoutSecondsRemaining", remaining));
            throw new AccountLockedException(remaining);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int newCount = loginAttemptTracker.recordFailed(userId);
            auditDenied(
                    request.email(),
                    userId,
                    user.getRol().name(),
                    ipOrigin,
                    "INVALID_CREDENTIALS",
                    builder -> builder.detail("attemptNumber", newCount));
            if (newCount >= MAX_LOGIN_ATTEMPTS) {
                loginAttemptTracker.lock(userId);
                auditAccountLocked(userId, user.getRol().name(), ipOrigin);
                notifier.sendAccountLockedEmail(
                        new AccountLockedEmailCommand(
                                userId.toString(),
                                user.getEmail(),
                                user.getNombreCompleto(),
                                LOCK_DURATION_MINUTES));
            }
            throw new InvalidCredentialsException();
        }

        // Éxito: resetea contador, crea sesión temporal + OTP, dispara email asíncrono.
        loginAttemptTracker.reset(userId);
        String otp = otpGenerator.generate();
        TempSessionData session =
                new TempSessionData(
                        userId.toString(),
                        user.getEmail(),
                        user.getRol().name(),
                        Instant.now());
        String tempSessionId = tempSessionManager.createSession(session, otp);

        auditAllowed(user.getEmail(), userId, user.getRol().name(), ipOrigin);

        notifier.sendOtpEmail(
                new OtpEmailCommand(
                        userId.toString(),
                        user.getEmail(),
                        user.getNombreCompleto(),
                        otp,
                        OTP_TTL_MINUTES));

        return new LoginResponse(tempSessionId, TEMP_SESSION_TTL_SECONDS);
    }

    private void auditAllowed(String email, UUID userId, String role, String ipOrigin) {
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.LOGIN_ATTEMPT)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId.toString())
                        .actorRole(role)
                        .ipOrigin(ipOrigin)
                        .detail("attemptedEmail", email)
                        .build());
    }

    private void auditDenied(
            String email,
            UUID userId,
            String role,
            String ipOrigin,
            String reason,
            DetailContributor extra) {
        AuditEvent.Builder builder =
                AuditEvent.builder()
                        .eventType(AuditEventType.LOGIN_ATTEMPT)
                        .resource(RESOURCE)
                        .result(AuditResult.DENIED)
                        .actorId(userId != null ? userId.toString() : null)
                        .actorRole(role)
                        .ipOrigin(ipOrigin)
                        .detail("attemptedEmail", email)
                        .detail("reason", reason);
        if (extra != null) {
            extra.apply(builder);
        }
        auditor.record(builder.build());
    }

    private void auditAccountLocked(UUID userId, String role, String ipOrigin) {
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.ACCOUNT_LOCKED)
                        .resource(RESOURCE)
                        .result(AuditResult.DENIED)
                        .actorId(userId.toString())
                        .actorRole(role)
                        .ipOrigin(ipOrigin)
                        .detail("reason", "MAX_LOGIN_ATTEMPTS")
                        .detail("lockDurationSeconds", LOCK_DURATION_SECONDS)
                        .build());
    }

    private void auditTechnical(String email, String ipOrigin, Throwable cause) {
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.LOGIN_ATTEMPT)
                        .resource(RESOURCE)
                        .result(AuditResult.DENIED)
                        .ipOrigin(ipOrigin)
                        .detail("attemptedEmail", email)
                        .detail("reason", "TECHNICAL_ERROR")
                        .detail("errorClass", cause.getClass().getName())
                        .build());
    }

    @FunctionalInterface
    private interface DetailContributor {
        void apply(AuditEvent.Builder builder);
    }
}
