package co.edu.unbosque.bloomtrade.unit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.edu.unbosque.bloomtrade.auth.ratelimit.LoginAttemptTracker;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Tests unitarios del {@link LoginAttemptTracker} (HU-F02 Lote F / T5.3). */
@ExtendWith(MockitoExtension.class)
class LoginAttemptTrackerTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String ATTEMPTS_KEY = "login:attempts:" + USER_ID;
    private static final String LOCKOUT_KEY = "lockout:" + USER_ID;

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> ops;

    private LoginAttemptTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LoginAttemptTracker(redis);
    }

    @Test
    void shouldIncrementAndSetTtlOnFirstFailure() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(ATTEMPTS_KEY)).thenReturn(1L);

        int count = tracker.recordFailed(USER_ID);

        assertThat(count).isEqualTo(1);
        verify(redis).expire(ATTEMPTS_KEY, Duration.ofHours(1));
    }

    @Test
    void shouldNotResetTtlOnSubsequentFailures() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(ATTEMPTS_KEY)).thenReturn(2L);

        int count = tracker.recordFailed(USER_ID);

        assertThat(count).isEqualTo(2);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void shouldReportLockedWhenLockoutKeyExists() {
        when(redis.hasKey(LOCKOUT_KEY)).thenReturn(true);

        assertThat(tracker.isLocked(USER_ID)).isTrue();
    }

    @Test
    void shouldReportNotLockedWhenLockoutKeyAbsent() {
        when(redis.hasKey(LOCKOUT_KEY)).thenReturn(false);

        assertThat(tracker.isLocked(USER_ID)).isFalse();
    }

    @Test
    void shouldReturnLockoutSecondsRemainingFromRedisTtl() {
        when(redis.getExpire(LOCKOUT_KEY, TimeUnit.SECONDS)).thenReturn(420L);

        assertThat(tracker.lockoutSecondsRemaining(USER_ID)).isEqualTo(420L);
    }

    @Test
    void shouldReturnZeroWhenLockoutHasNoTtlOrIsAbsent() {
        when(redis.getExpire(LOCKOUT_KEY, TimeUnit.SECONDS)).thenReturn(-2L);

        assertThat(tracker.lockoutSecondsRemaining(USER_ID)).isZero();
    }

    @Test
    void shouldSetLockoutWith15MinTtl() {
        when(redis.opsForValue()).thenReturn(ops);

        tracker.lock(USER_ID);

        verify(ops).set(eq(LOCKOUT_KEY), eq("1"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void shouldDeleteAttemptsKeyOnReset() {
        tracker.reset(USER_ID);

        verify(redis).delete(ATTEMPTS_KEY);
    }
}
