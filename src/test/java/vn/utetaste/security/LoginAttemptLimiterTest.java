package vn.utetaste.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {
    private final MutableClock clock = new MutableClock();
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginAttemptLimiter(clock, 10_000);
    }

    @Test
    void eightFailuresBlockOnlyTheMatchingAddressAndUsername() {
        fail("client-a", "waiter", 7);
        assertThat(limiter.isBlocked("client-a", "waiter")).isFalse();

        limiter.failed("client-a", "waiter");

        assertThat(limiter.isBlocked("client-a", "waiter")).isTrue();
        assertThat(limiter.isBlocked("client-a", "chef")).isFalse();
        assertThat(limiter.isBlocked("client-b", "waiter")).isFalse();
    }

    @Test
    void rotatingUsernamesCannotBypassTheAddressLimit() {
        for (int i = 0; i < 39; i++) {
            limiter.failed("client-a", "unknown-" + i);
        }
        assertThat(limiter.isBlocked("client-a", "new-name")).isFalse();

        limiter.failed("client-a", "unknown-39");

        assertThat(limiter.isBlocked("client-a", "new-name")).isTrue();
        assertThat(limiter.isBlocked("client-b", "new-name")).isFalse();
    }

    @Test
    void successfulLoginClearsPairFailuresButPreservesAddressFailures() {
        fail("client-a", "waiter", 7);
        limiter.succeeded("client-a", "waiter");
        fail("client-a", "waiter", 7);

        assertThat(limiter.isBlocked("client-a", "waiter")).isFalse();

        for (int i = 0; i < 26; i++) {
            limiter.failed("client-a", "unknown-" + i);
        }
        assertThat(limiter.isBlocked("client-a", "waiter")).isTrue();
    }

    @Test
    void fixedWindowExpiresAtExactlyFifteenMinutesWithoutBeingExtended() {
        limiter.failed("client-a", "waiter");
        clock.advance(Duration.ofMinutes(14));
        fail("client-a", "waiter", 7);
        clock.advance(Duration.ofSeconds(59));
        assertThat(limiter.isBlocked("client-a", "waiter")).isTrue();

        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.isBlocked("client-a", "waiter")).isFalse();
        limiter.failed("client-a", "waiter");
        assertThat(limiter.isBlocked("client-a", "waiter")).isFalse();
    }

    @Test
    void capacityExhaustionRejectsUnknownKeysWithoutEvictingBlockedUsers() {
        limiter = new LoginAttemptLimiter(clock, 4);
        fail("client-a", "waiter", 8);
        limiter.failed("client-b", "chef");

        assertThat(limiter.isBlocked("client-c", "manager")).isTrue();
        limiter.failed("client-c", "manager");
        assertThat(limiter.isBlocked("client-a", "waiter")).isTrue();
        assertThat(limiter.isBlocked("client-b", "chef")).isFalse();

        clock.advance(Duration.ofMinutes(15));

        assertThat(limiter.isBlocked("client-c", "manager")).isFalse();
        assertThat(limiter.isBlocked("client-a", "waiter")).isFalse();
    }

    @Test
    void capacityCheckAccountsForBothAddressAndPairKeys() {
        limiter = new LoginAttemptLimiter(clock, 3);
        limiter.failed("client-a", "waiter");

        assertThat(limiter.isBlocked("client-b", "chef")).isTrue();
        assertThat(limiter.isBlocked("client-a", "chef")).isFalse();
        limiter.failed("client-a", "chef");
        assertThat(limiter.isBlocked("client-a", "manager")).isTrue();
    }

    @Test
    void concurrentFailuresAreNotLost() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) {
                String username = "unknown-" + i;
                tasks.add(executor.submit(() -> limiter.failed("client-a", username)));
            }
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
            assertThat(limiter.isBlocked("client-a", "another-name")).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private void fail(String address, String username, int count) {
        for (int i = 0; i < count; i++) {
            limiter.failed(address, username);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
