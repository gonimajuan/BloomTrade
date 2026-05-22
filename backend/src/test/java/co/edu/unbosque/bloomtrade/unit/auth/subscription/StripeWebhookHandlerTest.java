package co.edu.unbosque.bloomtrade.unit.auth.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.StripeWebhookEvent;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.WebhookSignatureInvalidException;
import co.edu.unbosque.bloomtrade.auth.subscription.repository.StripeWebhookEventRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.repository.SubscriptionRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.service.StripeWebhookHandler;
import co.edu.unbosque.bloomtrade.integration.stripe.StripeAdapter;
import co.edu.unbosque.bloomtrade.notification.Notifier;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class StripeWebhookHandlerTest {

    private static final String IP = "203.0.113.42";
    private static final String RAW_BODY = "{\"id\":\"evt_test_xyz\",\"type\":\"foo.bar\"}";
    private static final String SIG = "t=123,v1=fake_signature";

    @Mock private StripeAdapter stripeAdapter;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private StripeWebhookEventRepository webhookEventRepository;
    @Mock private UserRepository userRepository;
    @Mock private Notifier notifier;
    @Mock private Auditor auditor;

    private StripeWebhookHandler handler;

    @BeforeEach
    void setUp() {
        handler =
                new StripeWebhookHandler(
                        stripeAdapter,
                        subscriptionRepository,
                        webhookEventRepository,
                        userRepository,
                        notifier,
                        auditor);
    }

    @Test
    void shouldRejectInvalidSignature() throws Exception {
        when(stripeAdapter.constructWebhookEvent(RAW_BODY, SIG))
                .thenThrow(new SignatureVerificationException("invalid", SIG));

        assertThatThrownBy(() -> handler.handle(RAW_BODY, SIG, IP))
                .isInstanceOf(WebhookSignatureInvalidException.class);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        assertThat(captor.getValue().eventType())
                .isEqualTo(AuditEventType.STRIPE_WEBHOOK_SIGNATURE_FAILED);

        // NO debe haber INSERT en stripe_webhook_events (no se llegó a procesar).
        verify(webhookEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldEmitDuplicateAndShortCircuitWhenEventIdAlreadyExists() throws Exception {
        Event event = stubEvent("evt_test_duplicate", "checkout.session.completed");
        when(stripeAdapter.constructWebhookEvent(RAW_BODY, SIG)).thenReturn(event);
        when(webhookEventRepository.saveAndFlush(any(StripeWebhookEvent.class)))
                .thenThrow(new DataIntegrityViolationException("uq_stripe_event_id"));

        handler.handle(RAW_BODY, SIG, IP); // should NOT throw

        // Verify audit DUPLICATE was emitted, but no further processing.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        assertThat(captor.getValue().eventType())
                .isEqualTo(AuditEventType.STRIPE_WEBHOOK_DUPLICATE);

        // El short-circuit evita la rama de procesamiento (no se consulta subscriptionRepository).
        verify(subscriptionRepository, never()).findByStripeSubscriptionId(anyString());
    }

    @Test
    void shouldIgnoreUnknownEventTypeButRecordReceived() throws Exception {
        Event event = stubEvent("evt_test_unknown", "customer.created"); // tipo no manejado
        when(stripeAdapter.constructWebhookEvent(RAW_BODY, SIG)).thenReturn(event);
        StripeWebhookEvent stored =
                StripeWebhookEvent.received(event.getId(), event.getType(), RAW_BODY);
        when(webhookEventRepository.saveAndFlush(any(StripeWebhookEvent.class)))
                .thenReturn(stored);

        handler.handle(RAW_BODY, SIG, IP); // no throw

        // Solo emite STRIPE_WEBHOOK_RECEIVED — no procesa ningún sub-handler.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor, times(1)).record(captor.capture());
        assertThat(captor.getValue().eventType())
                .isEqualTo(AuditEventType.STRIPE_WEBHOOK_RECEIVED);

        verify(subscriptionRepository, never()).findByStripeSubscriptionId(anyString());
        verify(notifier, never()).sendWelcomePremiumEmail(any());
    }

    /**
     * Stub mínimo de Event — los handlers de los 4 tipos requieren acceso al objeto interno
     * (Session, Subscription, Invoice), lo cual implica usar mocks complejos del SDK o llamadas
     * reales a Stripe. Esos flujos se cubren en E2E manual con {@code stripe-cli trigger}.
     */
    private Event stubEvent(String id, String type) {
        Event event = new Event();
        event.setId(id);
        event.setType(type);
        return event;
    }
}
