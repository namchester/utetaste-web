package vn.utetaste.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Failure limits for one application instance; no credentials are retained. */
@Component
public class LoginAttemptLimiter {
    private static final int PAIR_LIMIT = 8;
    private static final int ADDRESS_LIMIT = 40;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final int maxEntries;
    private final Map<Key, Attempt> attempts = new HashMap<>();
    private Instant nextCleanup;

    // A null username identifies the counter shared by all users at one address.
    private record Key(String address, String username) {}
    private record Attempt(int failures, Instant expiresAt) {}

    public LoginAttemptLimiter() {
        this(Clock.systemUTC(), 10_000);
    }

    LoginAttemptLimiter(Clock clock, int maxEntries) {
        this.clock = Objects.requireNonNull(clock);
        if (maxEntries < 2) {
            throw new IllegalArgumentException("At least two limiter entries are required.");
        }
        this.maxEntries = maxEntries;
        this.nextCleanup = clock.instant();
    }

    public synchronized boolean isBlocked(String address, String username) {
        Instant now = clock.instant();
        cleanup(now);
        var pair = pair(address, username);
        var ip = new Key(address, null);
        var pairAttempt = active(pair, now);
        var ipAttempt = active(ip, now);
        if ((pairAttempt != null && pairAttempt.failures() >= PAIR_LIMIT)
                || (ipAttempt != null && ipAttempt.failures() >= ADDRESS_LIMIT)) {
            return true;
        }
        // Do not evict live counters: username/IP rotation must not reset limits.
        return !hasCapacity(pairAttempt, ipAttempt);
    }

    public synchronized void failed(String address, String username) {
        Instant now = clock.instant();
        cleanup(now);
        var pair = pair(address, username);
        var ip = new Key(address, null);
        var pairAttempt = active(pair, now);
        var ipAttempt = active(ip, now);
        if (!hasCapacity(pairAttempt, ipAttempt)) {
            return;
        }
        attempts.put(pair, increment(pairAttempt, PAIR_LIMIT, now));
        attempts.put(ip, increment(ipAttempt, ADDRESS_LIMIT, now));
    }

    public synchronized void succeeded(String address, String username) {
        cleanup(clock.instant());
        attempts.remove(pair(address, username));
        // Keep address failures so one successful login cannot reset its budget.
    }

    private Key pair(String address, String username) {
        return new Key(Objects.requireNonNull(address), Objects.requireNonNull(username));
    }

    private boolean hasCapacity(Attempt pair, Attempt ip) {
        int needed = (pair == null ? 1 : 0) + (ip == null ? 1 : 0);
        return attempts.size() + needed <= maxEntries;
    }

    private Attempt active(Key key, Instant now) {
        var attempt = attempts.get(key);
        if (attempt != null && !attempt.expiresAt().isAfter(now)) {
            attempts.remove(key);
            return null;
        }
        return attempt;
    }

    private Attempt increment(Attempt previous, int limit, Instant now) {
        return previous == null
                ? new Attempt(1, now.plus(WINDOW))
                : new Attempt(Math.min(limit, previous.failures() + 1), previous.expiresAt());
    }

    private void cleanup(Instant now) {
        if (!now.isBefore(nextCleanup)) {
            attempts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            nextCleanup = now.plus(CLEANUP_INTERVAL);
        }
    }
}
