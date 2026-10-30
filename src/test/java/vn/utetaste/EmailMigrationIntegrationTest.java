package vn.utetaste;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers @EnabledIfSystemProperty(named="postgresIT",matches="true")
class EmailMigrationIntegrationTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void upgradeV1PreservesStaffAndEnforcesNewConstraints() throws Exception {
        Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).target("1").load().migrate();
        try(var connection=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var sql=connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_users(username,password_hash,full_name,role,active,token_version) VALUES('legacy_admin','old-test-hash','Legacy','ADMIN',true,7)");
            var result=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().migrate();
            assertThat(result.migrationsExecuted).isEqualTo(2); // V2 email + additive V3 members; V1 remains unchanged.
            try(var row=sql.executeQuery("SELECT password_hash,token_version,email,email_verified FROM app_users WHERE username='legacy_admin'")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("old-test-hash");assertThat(row.getInt(2)).isEqualTo(7);assertThat(row.getString(3)).isNull();assertThat(row.getBoolean(4)).isFalse();
            }
            sql.executeUpdate("INSERT INTO app_users(username,password_hash,full_name,role,email,email_verified) VALUES('customer','test-hash','Customer','USER','customer@example.test',true)");
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO app_users(username,password_hash,full_name,role,email) VALUES('duplicate','h','D','USER','customer@example.test')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO app_users(username,password_hash,full_name,role,email) VALUES('uppercase','h','U','USER','Upper@example.test')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO app_users(username,password_hash,full_name,role) VALUES('missing_email','h','M','USER')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("INSERT INTO app_users(username,password_hash,full_name,role) VALUES('missing_branch','h','M','WAITER')")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->sql.executeUpdate("UPDATE app_users SET email_verified=true WHERE username='legacy_admin'")).isInstanceOf(SQLException.class);
            assertThat(Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().validateWithResult().validationSuccessful).isTrue();
        }
    }
}
