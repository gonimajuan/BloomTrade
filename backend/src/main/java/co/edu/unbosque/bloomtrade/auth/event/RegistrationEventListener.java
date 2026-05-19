package co.edu.unbosque.bloomtrade.auth.event;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEvent.AuditResult;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import co.edu.unbosque.bloomtrade.notification.dto.WelcomeEmailCommand;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacciona al registro <strong>después</strong> del commit (spec HU-F01 §5.1 pasos 13–14):
 * emite {@code USER_REGISTERED} a auditoría y dispara el email de bienvenida (asíncrono).
 * {@code fallbackExecution=true} para que también opere si no hubo transacción activa.
 */
@Component
public class RegistrationEventListener {

    private static final String RESOURCE = "/api/v1/auth/register";

    private final Auditor auditor;
    private final Notifier notifier;

    public RegistrationEventListener(Auditor auditor, Notifier notifier) {
        this.auditor = auditor;
        this.notifier = notifier;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserRegistered(UserRegisteredEvent event) {
        String userId = event.userId().toString();
        auditor.record(
                AuditEvent.builder()
                        .eventType(AuditEventType.USER_REGISTERED)
                        .resource(RESOURCE)
                        .result(AuditResult.ALLOWED)
                        .actorId(userId)
                        .actorRole(event.rol().name())
                        .ipOrigin(event.ipOrigin())
                        .detail("userId", userId)
                        .detail("email", event.email())
                        .detail("rol", event.rol().name())
                        .detail("registrationMethod", "WEB_FORM")
                        .build());

        notifier.sendWelcomeEmail(
                new WelcomeEmailCommand(userId, event.email(), event.nombreCompleto()));
    }
}
