package vn.utetaste;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Runs the complete email/security/concurrency contract against an isolated PostgreSQL database. */
@Testcontainers
@EnabledIfSystemProperty(named="postgresIT",matches="true")
class EmailAccountPostgresIntegrationTest extends EmailAccountIntegrationTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);
        r.add("spring.datasource.password",pg::getPassword);r.add("spring.flyway.enabled",()->true);
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
}
