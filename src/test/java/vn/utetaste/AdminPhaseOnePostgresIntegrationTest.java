package vn.utetaste;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers @EnabledIfSystemProperty(named="postgresIT",matches="true")
class AdminPhaseOnePostgresIntegrationTest extends AdminPhaseOneIntegrationTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);r.add("spring.flyway.enabled",()->true);r.add("spring.jpa.hibernate.ddl-auto",()->"validate");}
}
