package co.edu.unbosque.bloomtrade.auth.service;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.dto.MfaResendRequest;
import co.edu.unbosque.bloomtrade.auth.dto.MfaResendResponse;
import co.edu.unbosque.bloomtrade.auth.dto.MfaVerifyRequest;
import co.edu.unbosque.bloomtrade.auth.dto.MfaVerifyResponse;
import co.edu.unbosque.bloomtrade.auth.dto.UserSummary;
import co.edu.unbosque.bloomtrade.auth.exception.MaxResendsExceededException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaCodeExpiredException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaInvalidCodeException;
import co.edu.unbosque.bloomtrade.auth.exception.MfaSessionInvalidatedException;
import co.edu.unbosque.bloomtrade.auth.exception.ResendCooldownActiveException;
import co.edu.unbosque.bloomtrade.auth.exception.TempSessionInvalidException;
import co.edu.unbosque.bloomtrade.auth.ratelimit.MfaAttemptTracker;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.security.TokenIssuer;
import co.edu.unbosque.bloomtrade.auth.session.OtpGenerator;
import co.edu.unbosque.bloomtrade.auth.session.TempSessionData;
import co.edu.unbosque.bloomtrade.auth.session.TempSessionManager;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.OtpEmailCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquesta el paso 2 del flujo de autenticación: verificación de OTP (spec HU-F02 §5.1 pasos 18-31)
 * y reenvío de OTP (spec §5.2.3).
 *
 * <p>Decisión D18: no hay refresh token ni cookie — solo se emite el access token y se devuelve en
 * el body. La sesión temporal se borra de Redis tras emitir el token (un OTP es de un solo uso).
 *
 * <p>Las claves Redis {@code mfa:attempts:*} y {@code mfa:resends:*} las inicializa
 * {@code TempSessionManager} al nacer la sesión; este servicio sólo las lee/incrementa vía
 * {@link MfaAttemptTracker}.
 */
@Service
public class MfaService {

    private static final String VERIFY_RESOURCE = "/api/v1/auth/mfa/verify";
    private static final String RESEND_RESOURCE = "/api/v1/auth/mfa/resend";
    private static final int MAX_MFA_ATTEMPTS = 3;
    private static final int MAX_RESENDS = 3;
    private static final int OTP_TTL_MINUTES = 5;
    private static final int TEMP_SESSION_TTL_SECONDS = 300;

    private final TempSessionManager tempSessionManager;
    private final MfaAttemptTracker mfaAttemptTracker;
    private final OtpGenerator otpGenerator;
    private final TokenIssuer tokenIssuer;
    private final UserRepository userRepository;
    private final Notifier notifier;
    private final Auditor auditor;

    public MfaService(
            TempSessionManager tempSessionManager,
            MfaAttemptTracker mfaAttemptTracker,
            OtpGenerator otpGenerator,
            TokenIssuer tokenIssuer,
            UserRepository userRepository,
            Notifier notifier,
            Auditor auditor) {
        this.tempSessionManager = tempSessionManager;
        this.mfaAttemptTracker = mfaAttemptTracker;
        this.otpGenerator = otpGenerator;
        this.tokenIssuer = tokenIssuer;
        this.userRepository = userRepository;
        this.notifier = notifier;
        this.auditor = auditor;
    }

    @Transactional(readOnly = true)
    public MfaVerifyResponse verify(MfaVerifyRequest request, String ipOrigin) {
        Optional<TempSessionData> sessionOpt =
                tempSessionManager.getSession(request.tempSessionId());
        if (sessionOpt.isEmpty()) {
            auditMfaFailed(null, null, VERIFY_RESOURCE, ipOrigin, "SESSION_EXPIRED", 0);
            throw new TempSessionInvalidException();
        }
        TempSessionData session = sessionOpt.get();

        Optional<String> storedOtpOpt = tempSessionManager.getOtp(request.tempSessionId());
        if (storedOtpOpt.isEmpty()) {
            auditMfaFailed(
                    session.userId(),
                    session.role(),
                    VERIFY_RESOURCE,
                    ipOrigin,
                    "CODE_EXPIRED",
                    0);
            throw new MfaCodeExpiredException();
        }

        if (!otpGenerator.matches(request.code(), storedOtpOpt.get())) {
            int newAttempts = mfaAttemptTracker.recordFailed(request.tempSessionId());
            auditMfaFailed(
                    session.userId(),
                    session.role(),
                    VERIFY_RESOURCE,
                    ipOrigin,
                    "INVALID_CODE",
                    newAttempts);
            if (newAttempts >= MAX_MFA_ATTEMPTS) {
                tempSessionManager.invalidate(request.tempSessionId());
                auditSessionInvalidated(
                        session.userId(),
                        session.role(),
                        VERIFY_RESOURCE,
                        ipOrigin,
                        "MAX_ATTEMPTS");
                throw new MfaSessionInvalidatedException();
            }
            throw new MfaInvalidCodeException(MAX_MFA_ATTEMPTS - newAttempts);
        }

        // OTP válido: cargar el usuario, emitir token, invalidar sesión, auditar.
        UUID userId = UUID.fromString(session.userId());
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Usuario de la sesión temporal no encontrado: "
                                                        + userId));
        TokenIssuer.IssuedAccessToken issued =
                tokenIssuer.issueAccessToken(userId, session.role());
        tempSessionManager.invalidate(request.tempSessionId());

        long sessionDurationMs =
                Duration.between(session.createdAt(), Instant.now()).toMillis();
        auditMfaVerified(session.userId(), session.role(), ipOrigin, sessionDurationMs);

        UserSummary summary =
                new UserSummary(
                        user.getId(),
                        user.getEmail(),
                        user.getNombreCompleto(),
                        user.getRol());
        return new MfaVerifyResponse(
                issued.accessToken(), issued.expiresInSeconds(), summary);
    }

    @Transactional(readOnly = true)
    public MfaResendResponse resend(MfaResendRequest request, String ipOrigin) {
        Optional<TempSessionData> sessionOpt =
                tempSessionManager.getSession(request.tempSessionId());
        if (sessionOpt.isEmpty()) {
            auditMfaFailed(null, null, RESEND_RESOURCE, ipOrigin, "SESSION_EXPIRED", 0);
            throw new TempSessionInvalidException();
        }
        TempSessionData session = sessionOpt.get();

        int currentResends = mfaAttemptTracker.getResendCount(request.tempSessionId());
        if (currentResends >= MAX_RESENDS) {
            tempSessionManager.invalidate(request.tempSessionId());
            auditSessionInvalidated(
                    session.userId(),
                    session.role(),
                    RESEND_RESOURCE,
                    ipOrigin,
                    "MAX_RESENDS");
            throw new MaxResendsExceededException();
        }

        if (mfaAttemptTracker.isOnCooldown(request.tempSessionId())) {
            long remaining =
                    mfaAttemptTracker.cooldownSecondsRemaining(request.tempSessionId());
            throw new ResendCooldownActiveException(remaining);
        }

        // Generar nuevo OTP, sobrescribir, contar el resend, armar cooldown, despachar email.
        String newOtp = otpGenerator.generate();
        tempSessionManager.replaceOtp(request.tempSessionId(), newOtp);
        int newResendCount = mfaAttemptTracker.recordResend(request.tempSessionId());
        mfaAttemptTracker.setCooldown(request.tempSessionId());

        UUID userId = UUID.fromString(session.userId());
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Usuario de la sesión temporal no encontrado: "
                                                        + userId));
        notifier.sendOtpEmail(
                new OtpEmailCommand(
                        session.userId(),
                        session.email(),
                        user.getNombreCompleto(),
                        newOtp,
                        OTP_TTL_MINUTES));

        auditMfaResendRequested(
                session.userId(), session.role(), ipOrigin, newResendCount);

        return new MfaResendResponse(
                TEMP_SESSION_TTL_SECONDS, MAX_RESENDS - newResendCount);
    }

    private void auditMfaVerified(
            String userId, String role, String ipOrigin, long sessionDurationMs) {
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.MFA_VERIFIED)
                        .resource(VERIFY_RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .actorRole(role)
                        .ipOrigin(ipOrigin)
                        .detail("tempSessionDurationMs", sessionDurationMs)
                        .build());
    }

    private void auditMfaFailed(
            String userId,
            String role,
            String resource,
            String ipOrigin,
            String reason,
            int attemptNumber) {
        AuditEvent.Builder builder =
                AuditEvent.builder()
                        .eventType(AuditEventType.MFA_FAILED)
                        .resource(resource)
                        .result(AuditResult.DENIED)
                        .actorId(userId)
                        .actorRole(role)
                        .ipOrigin(ipOrigin)
                        .detail("reason", reason);
        if (attemptNumber > 0) {
            builder.detail("attemptNumber", attemptNumber);
        }
        auditor.record(builder.build());
    }

    private void auditMfaResendRequested(
            String userId, String role, String ipOrigin, int resendNumber) {
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.MFA_RESEND_REQUESTED)
                        .resource(RESEND_RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .actorRole(role)
                        .ipOrigin(ipOrigin)
                        .detail("resendNumber", resendNumber)
                        .build());
    }

    private void auditSessionInvalidated(
            String userId,
            String role,
            String resource,
            String ipOrigin,
            String reason) {
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.MFA_SESSION_INVALIDATED)
                        .resource(resource)
                        .result(AuditResult.DENIED)
                        .actorId(userId)
                        .actorRole(role)
                        .ipOrigin(ipOrigin)
                        .detail("reason", reason)
                        .build());
    }
}
