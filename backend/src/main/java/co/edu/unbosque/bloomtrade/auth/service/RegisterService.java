package co.edu.unbosque.bloomtrade.auth.service;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.dto.RegisterRequest;
import co.edu.unbosque.bloomtrade.auth.dto.RegisterResponse;
import co.edu.unbosque.bloomtrade.auth.event.UserRegisteredEvent;
import co.edu.unbosque.bloomtrade.auth.exception.EmailAlreadyRegisteredException;
import co.edu.unbosque.bloomtrade.auth.exception.RegistrationTechnicalException;
import co.edu.unbosque.bloomtrade.auth.mapper.UserMapper;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.portfolio.service.BalanceInitializer;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquesta el registro de un inversionista (spec HU-F01 §5.1). En una sola transacción ACID:
 * verifica unicidad de email, hashea el password (BCrypt 12), crea {@code User} + balance inicial
 * (vía {@link BalanceInitializer}, D4) y publica {@link UserRegisteredEvent} (auditoría y email
 * son post-commit, D5). Los fallos emiten {@code USER_REGISTRATION_FAILED} (spec §5.3.1/§5.3.5).
 */
@Service
public class RegisterService {

    private static final String RESOURCE = "/api/v1/auth/register";

    private final UserRepository userRepository;
    private final BalanceInitializer balanceInitializer;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final Auditor auditor;
    private final ApplicationEventPublisher eventPublisher;

    public RegisterService(
            UserRepository userRepository,
            BalanceInitializer balanceInitializer,
            PasswordEncoder passwordEncoder,
            UserMapper userMapper,
            Auditor auditor,
            ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.balanceInitializer = balanceInitializer;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.auditor = auditor;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request, String ipOrigin) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            auditEmailDuplicate(request.email(), ipOrigin);
            throw new EmailAlreadyRegisteredException(request.email());
        }
        try {
            User user =
                    userRepository.saveAndFlush(
                            User.register(
                                    request.email(),
                                    passwordEncoder.encode(request.password()),
                                    request.nombreCompleto(),
                                    request.tipoDocumento(),
                                    request.numeroDocumento(),
                                    request.telefono(),
                                    Instant.now()));

            balanceInitializer.initializeBalance(user.getId());

            eventPublisher.publishEvent(
                    new UserRegisteredEvent(
                            user.getId(),
                            user.getEmail(),
                            user.getNombreCompleto(),
                            user.getRol(),
                            ipOrigin));

            return userMapper.toResponse(user);
        } catch (DataIntegrityViolationException e) {
            // Carrera contra el índice único idx_users_email_lower.
            auditEmailDuplicate(request.email(), ipOrigin);
            throw new EmailAlreadyRegisteredException(request.email());
        } catch (DataAccessException e) {
            // Excepción específica (no genérica, CLAUDE.md #11). Stack completo al log, NO al
            // evento de auditoría (PII, spec §5.3.5).
            auditor.record(
                    AuditEvent.builder()
                            .eventType(AuditEventType.USER_REGISTRATION_FAILED)
                            .resource(RESOURCE)
                            .result(AuditResult.DENIED)
                            .ipOrigin(ipOrigin)
                            .detail("attemptedEmail", request.email())
                            .detail("reason", "TECHNICAL_ERROR")
                            .detail("errorClass", e.getClass().getName())
                            .build());
            throw new RegistrationTechnicalException("Fallo técnico al registrar usuario", e);
        }
    }

    private void auditEmailDuplicate(String attemptedEmail, String ipOrigin) {
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.USER_REGISTRATION_FAILED)
                        .resource(RESOURCE)
                        .result(AuditResult.DENIED)
                        .ipOrigin(ipOrigin)
                        .detail("attemptedEmail", attemptedEmail)
                        .detail("reason", "EMAIL_DUPLICATE")
                        .build());
    }
}
