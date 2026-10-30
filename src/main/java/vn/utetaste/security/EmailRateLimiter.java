package vn.utetaste.security;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.utetaste.service.BusinessException;

/** Bounded, fail-closed limiter for this single-instance application. Account send quotas also live in DB. */
@Component
public class EmailRateLimiter {
    private record Counter(int count, Instant expires) {}
    private final Map<String, Counter> counters = new HashMap<>();
    private final Clock clock;
    public EmailRateLimiter(Clock clock) { this.clock=clock; }
    public synchronized void take(String key, int limit) {
        Instant now=clock.instant();
        counters.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
        Counter old=counters.get(key);
        if ((old!=null && old.count()>=limit) || (old==null && counters.size()>=10000))
            throw new BusinessException("Quá nhiều yêu cầu. Vui lòng thử lại sau 15 phút.");
        counters.put(key,new Counter(old==null?1:old.count()+1,old==null?now.plusSeconds(900):old.expires()));
    }
}
