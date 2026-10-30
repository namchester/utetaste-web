package vn.utetaste.config;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class EmailSecurityConfig {
    @Bean Clock emailClock() { return Clock.systemUTC(); }
}
