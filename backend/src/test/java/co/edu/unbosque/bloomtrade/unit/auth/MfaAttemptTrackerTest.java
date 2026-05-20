package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.auth.ratelimit.MfaAttemptTracker;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Tests unitarios del {@link MfaAttemptTracker} (HU-F02 Lote F / T5.3). */
@ExtendWith(MockitoExtension.class)
class MfaAttemptTrackerTest {

    private static final String SESSION_ID = "7f3a2c1b-9e4d-4f6e-8a7d-2b9c1e5f8a3b";
    private static final String ATTEMPTS_KEY = "mfa:attempts:" + SESSION_ID;
    private static final String RESENDS_KEY = "mfa:resends:" + SESSION_ID;
    private static final String COOLDOWN_KEY = "mfa:resend-cooldown:" + SESSION_ID;

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> ops;

    private MfaAttemptTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new MfaAttemptTracker(redis);
    }

    @Test
    void shouldIncrementAttemptCount() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(ATTEMPTS_KEY)).thenReturn(2L);

        assertThat(tracker.recordFailed(SESSION_ID)).isEqualTo(2);
    }

    @Test
    void shouldIncrementResendCount() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(RESENDS_KEY)).thenReturn(1L);

        assertThat(tracker.recordResend(SESSION_ID)).isEqualTo(1);
    }

    @Test
    void shouldReadResendCountFromRedis() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(RESENDS_KEY)).thenReturn("2");

        assertThat(tracker.getResendCount(SESSION_ID)).isEqualTo(2);
    }

    @Test
    void shouldDefaultToZeroWhenResendCounterMissing() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(RESENDS_KEY)).thenReturn(null);

        assertThat(tracker.getResendCount(SESSION_ID)).isZero();
    }

    @Test
    void shouldDefaultToZeroWhenResendCounterIsCorrupt() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(RESENDS_KEY)).thenReturn("not-a-number");

        assertThat(tracker.getResendCount(SESSION_ID)).isZero();
    }

    @Test
    void shouldReportCooldownActiveWhenCooldownKeyExists() {
        when(redis.hasKey(COOLDOWN_KEY)).thenReturn(true);

        assertThat(tracker.isOnCooldown(SESSION_ID)).isTrue();
    }

    @Test
    void shouldReadCooldownSecondsFromRedisTtl() {
        when(redis.getExpire(COOLDOWN_KEY, TimeUnit.SECONDS)).thenReturn(18L);

        assertThat(tracker.cooldownSecondsRemaining(SESSION_ID)).isEqualTo(18L);
    }

    @Test
    void shouldSetCooldownWith30SecondTtl() {
        when(redis.opsForValue()).thenReturn(ops);

        tracker.setCooldown(SESSION_ID);

        verify(ops).set(eq(COOLDOWN_KEY), eq("1"), eq(Duration.ofSeconds(30)));
    }
}
