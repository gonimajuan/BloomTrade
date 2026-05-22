package co.edu.unbosque.bloomtrade.unit.auth.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.unbosque.bloomtrade.auth.subscription.domain.BillingPlan;
import co.edu.unbosque.bloomtrade.auth.subscription.domain.Subscription;
import co.edu.unbosque.bloomtrade.auth.subscription.dto.SubscriptionDto;
import co.edu.unbosque.bloomtrade.auth.subscription.mapper.SubscriptionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HU-F06 §10.2 constraint NO-NEGOCIABLE: el JSON serializado de {@link SubscriptionDto}
 * <strong>nunca</strong> contiene {@code stripeCustomerId}, {@code stripeSubscriptionId} ni las
 * substrings {@code cus_} / {@code sub_}.
 */
class SubscriptionMapperTest {

    private static final String CUS_ID = "cus_TEST123456789ABCDEF";
    private static final String SUB_ID = "sub_TEST123456789ABCDEF";

    private SubscriptionMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(SubscriptionMapper.class);
        objectMapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void shouldMapVisibleFieldsFromSubscription() {
        Subscription sub = sampleSubscription();

        SubscriptionDto dto = mapper.toDto(sub);

        assertThat(dto.id()).isEqualTo(sub.getId());
        assertThat(dto.plan()).isEqualTo(sub.getPlan());
        assertThat(dto.status()).isEqualTo(sub.getStatus());
        assertThat(dto.currentPeriodStart()).isEqualTo(sub.getCurrentPeriodStart());
        assertThat(dto.currentPeriodEnd()).isEqualTo(sub.getCurrentPeriodEnd());
        assertThat(dto.cancelAtPeriodEnd()).isEqualTo(sub.isCancelAtPeriodEnd());
        assertThat(dto.createdAt()).isEqualTo(sub.getCreatedAt());
    }

    @Test
    void shouldNotLeakStripeIdsInSerializedJson() throws Exception {
        Subscription sub = sampleSubscription();
        SubscriptionDto dto = mapper.toDto(sub);

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json)
                .as("JSON expuesto al cliente no debe contener stripeCustomerId")
                .doesNotContain("stripeCustomerId")
                .doesNotContain("stripe_customer_id")
                .doesNotContain(CUS_ID)
                .doesNotContain("cus_")
                .as("JSON expuesto al cliente no debe contener stripeSubscriptionId")
                .doesNotContain("stripeSubscriptionId")
                .doesNotContain("stripe_subscription_id")
                .doesNotContain(SUB_ID)
                .doesNotContain("sub_");
    }

    private Subscription sampleSubscription() {
        Subscription sub =
                Subscription.activate(
                        UUID.randomUUID(),
                        CUS_ID,
                        SUB_ID,
                        BillingPlan.MONTHLY,
                        Instant.parse("2026-05-21T00:00:00Z"),
                        Instant.parse("2026-06-21T00:00:00Z"));
        ReflectionTestUtils.setField(sub, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(sub, "createdAt", Instant.parse("2026-05-21T00:00:00Z"));
        return sub;
    }
}
