package co.edu.unbosque.bloomtrade.unit.auth.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.audit.AuditEvent;
import co.edu.unbosque.bloomtrade.audit.AuditEventType;
import co.edu.unbosque.bloomtrade.audit.Auditor;
import co.edu.unbosque.bloomtrade.auth.domain.DocumentType;
import co.edu.unbosque.bloomtrade.auth.domain.User;
import co.edu.unbosque.bloomtrade.auth.repository.UserRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.Subscription;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.SubscriptionStatus;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.CheckoutSessionResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.PortalSessionResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.SubscriptionStatusResponse;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.NoStripeCustomerException;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.StripeApiException;
import co.edu.unbosque.bloomtrade.auth.subscription.exception.SubscriptionAlreadyActiveException;
import co.edu.unbosque.bloomtrade.auth.subscription.mapper.SubscriptionMapper;
import co.edu.unbosque.bloomtrade.auth.subscription.repository.SubscriptionRepository;
import co.edu.unbosque.bloomtrade.auth.subscription.service.SubscriptionService;
import co.edu.unbosque.bloomtrade.integration.stripe.StripeAdapter;
import com.stripe.model.checkout.Session;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final String IP = "203.0.113.7";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String CUS_ID = "cus_TEST_CUSTOMER_ID";
    private static final String SUB_ID = "sub_TEST_SUBSCRIPTION_ID";

    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionMapper subscriptionMapper;
    @Mock private StripeAdapter stripeAdapter;
    @Mock private Auditor auditor;

    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        service =
                new SubscriptionService(
                        userRepository,
                        subscriptionRepository,
                        subscriptionMapper,
                        stripeAdapter,
                        auditor);
    }

    private User sampleUser(String stripeCustomerId) {
        User user =
                User.register(
                        "juan@example.com",
                        "$2a$12$hash",
                        "Juan Pérez",
                        DocumentType.CC,
                        "1234567890",
                        "+573001234567",
                        Instant.now());
        ReflectionTestUtils.setField(user, "id", USER_ID);
        if (stripeCustomerId != null) {
            user.linkStripeCustomer(stripeCustomerId);
        }
        return user;
    }

    private Session stubStripeSession() {
        Session session = new Session();
        session.setId("cs_test_abc123");
        session.setUrl("https://checkout.stripe.com/c/pay/cs_test_abc123");
        return session;
    }

    @Test
    void shouldCreateCheckoutSessionAndCreateCustomerWhenUserHasNone() {
        User user = sampleUser(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(stripeAdapter.createCustomer(eq(user.getEmail()), eq(user.getNombreCompleto()), eq(USER_ID)))
                .thenReturn(CUS_ID);
        when(stripeAdapter.createCheckoutSession(eq(CUS_ID), eq(BillingPlan.MONTHLY), eq(USER_ID)))
                .thenReturn(stubStripeSession());

        CheckoutSessionResponse response =
                service.createCheckoutSession(USER_ID, BillingPlan.MONTHLY, IP);

        assertThat(response.checkoutUrl()).startsWith("https://checkout.stripe.com");
        assertThat(user.getStripeCustomerId()).isEqualTo(CUS_ID);
        verify(stripeAdapter).createCustomer(any(), any(), eq(USER_ID));
        verify(auditor)
                .record(any(AuditEvent.class));
    }

    @Test
    void shouldReuseExistingStripeCustomerOnSecondCheckout() {
        User user = sampleUser(CUS_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(stripeAdapter.createCheckoutSession(eq(CUS_ID), eq(BillingPlan.YEARLY), eq(USER_ID)))
                .thenReturn(stubStripeSession());

        service.createCheckoutSession(USER_ID, BillingPlan.YEARLY, IP);

        // No debe crear otro customer (Stripe Customers son reusables)
        verify(stripeAdapter, never()).createCustomer(any(), any(), any());
    }

    @Test
    void shouldReject409WhenAlreadyActiveSubscription() {
        Subscription existing =
                Subscription.activate(
                        USER_ID,
                        CUS_ID,
                        SUB_ID,
                        BillingPlan.MONTHLY,
                        Instant.now(),
                        Instant.now().plusSeconds(86400));
        when(subscriptionRepository.findByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingPlan.MONTHLY, IP))
                .isInstanceOf(SubscriptionAlreadyActiveException.class);

        verify(stripeAdapter, never()).createCheckoutSession(any(), any(), any());
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldAuditCHECKOUT_SESSION_FAILEDAndRethrowOnStripeError() {
        User user = sampleUser(CUS_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(stripeAdapter.createCheckoutSession(any(), any(), any()))
                .thenThrow(new StripeApiException("Stripe down", "api_error", new RuntimeException()));

        assertThatThrownBy(() -> service.createCheckoutSession(USER_ID, BillingPlan.MONTHLY, IP))
                .isInstanceOf(StripeApiException.class);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        assertThat(captor.getValue().eventType())
                .isEqualTo(AuditEventType.CHECKOUT_SESSION_FAILED);
        assertThat(captor.getValue().details())
                .containsEntry("reason", "STRIPE_API_ERROR")
                .containsEntry("stripeErrorCode", "api_error");
    }

    @Test
    void shouldOpenBillingPortalWhenStripeCustomerExists() {
        User user = sampleUser(CUS_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        com.stripe.model.billingportal.Session portalSession =
                new com.stripe.model.billingportal.Session();
        portalSession.setId("bps_test");
        portalSession.setUrl("https://billing.stripe.com/p/session/test_xyz");
        when(stripeAdapter.createBillingPortalSession(CUS_ID)).thenReturn(portalSession);

        PortalSessionResponse response = service.openBillingPortal(USER_ID, IP);

        assertThat(response.portalUrl()).startsWith("https://billing.stripe.com");
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditor).record(captor.capture());
        assertThat(captor.getValue().eventType())
                .isEqualTo(AuditEventType.BILLING_PORTAL_SESSION_CREATED);
    }

    @Test
    void shouldReject409WhenOpeningPortalWithoutStripeCustomer() {
        User user = sampleUser(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.openBillingPortal(USER_ID, IP))
                .isInstanceOf(NoStripeCustomerException.class);

        verify(stripeAdapter, never()).createBillingPortalSession(any());
        verify(auditor, never()).record(any());
    }

    @Test
    void shouldReturnIsPremiumTrueWhenActiveExists() {
        when(subscriptionRepository.existsByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE))
                .thenReturn(true);
        assertThat(service.isPremium(USER_ID)).isTrue();
    }

    @Test
    void shouldReturnIsPremiumFalseWhenNoActive() {
        when(subscriptionRepository.existsByUserIdAndStatus(USER_ID, SubscriptionStatus.ACTIVE))
                .thenReturn(false);
        assertThat(service.isPremium(USER_ID)).isFalse();
    }

    @Test
    void shouldReturnStatusWithNullSubscriptionWhenUserNeverHadOne() {
        when(subscriptionRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
                .thenReturn(Optional.empty());

        SubscriptionStatusResponse response = service.getStatus(USER_ID);

        assertThat(response.isPremium()).isFalse();
        assertThat(response.subscription()).isNull();
    }
}
