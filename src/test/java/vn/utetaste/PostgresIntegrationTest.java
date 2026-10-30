package vn.utetaste;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;

/** Opt in with -DpostgresIT=true; requires a running Docker engine. */
@SpringBootTest @ActiveProfiles("test") @Testcontainers
@EnabledIfSystemProperty(named="postgresIT",matches="true")
class PostgresIntegrationTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);r.add("spring.flyway.enabled",()->true);r.add("spring.jpa.hibernate.ddl-auto",()->"validate");}
    @Autowired OrderService ops;@Autowired AppUserRepository users;@Autowired RestaurantTableRepository tables;@Autowired DishRepository dishes;
    @Test void migrationsAndPostgresCheckoutWork(){var u=users.findByUsername("admin").orElseThrow();var a=new Actor(u.getId(),u.getUsername(),u.getFullName(),Role.ADMIN,null,null);var s=ops.openTable(a,tables.findAll().get(0).getId());var g=new Actor(null,"guest:"+s.getId(),"guest",Role.GUEST,s.getTable().getBranch().getId(),s.getId());var d=dishes.findAll().get(0);var o=ops.place(g,new OrderService.PlaceRequest(UUID.randomUUID().toString(),List.of(new OrderService.Line(d.getId(),1)),""));for(var state:List.of(OrderStatus.CONFIRMED,OrderStatus.COOKING,OrderStatus.READY,OrderStatus.SERVED))ops.changeStatus(a,o.id(),state);assertThat(ops.checkout(a,s.getId(),0,PaymentMethod.CASH).getTotal()).isEqualByComparingTo(d.getPrice());}
}
