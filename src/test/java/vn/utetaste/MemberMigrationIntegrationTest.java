package vn.utetaste;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers @EnabledIfSystemProperty(named="postgresIT",matches="true")
class MemberMigrationIntegrationTest {
    @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void upgradeV2PreservesEveryLegacyRowAndProtectsNewRelations() throws Exception {
        Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).target("2").load().migrate();
        try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());var s=c.createStatement()){
            s.executeUpdate("INSERT INTO branches(id,name,address,phone) VALUES(100,'Legacy branch','Legacy address','0901234567')");
            s.executeUpdate("INSERT INTO app_users(id,username,password_hash,full_name,role,active,token_version) VALUES(100,'legacy_admin','preserved-test-hash','Legacy','ADMIN',true,7)");
            s.executeUpdate("INSERT INTO app_users(id,username,password_hash,full_name,role,email,email_verified) VALUES(101,'legacy_user','old-user-test-hash','Customer','USER','legacy@example.test',true)");
            s.executeUpdate("INSERT INTO categories(id,name) VALUES(100,'Legacy category')");
            s.executeUpdate("INSERT INTO dishes(id,name,description,price,category_id,image_url,image_public_id) VALUES(100,'Legacy dish','Original description',50000,100,'/images/food/tea.jpg','preserved-photo-id')");
            s.executeUpdate("INSERT INTO restaurant_tables(id,branch_id,label,capacity,qr_key) VALUES(100,100,'Legacy table',4,'legacy-test-qr')");
            s.executeUpdate("INSERT INTO dining_sessions(id,table_id,opened_at) VALUES(100,100,now())");
            s.executeUpdate("INSERT INTO food_orders(id,session_id,status,note,request_key,created_at,updated_at,version) VALUES(100,100,'SERVED','Legacy note','00000000-0000-4000-8000-000000000001',now(),now(),4)");
            s.executeUpdate("INSERT INTO order_items(id,order_id,dish_id,dish_name,unit_price,quantity) VALUES(100,100,100,'Original name',50000,2)");
            s.executeUpdate("INSERT INTO invoices(id,session_id,subtotal,discount_amount,total,method,paid_at,cashier) VALUES(100,100,100000,10000,90000,'CASH',now(),'Original cashier')");
            var result=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().migrate();assertThat(result.migrationsExecuted).isEqualTo(1);
            try(var r=s.executeQuery("SELECT password_hash,token_version,email_verified,phone,created_at FROM app_users WHERE id=101")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("old-user-test-hash");assertThat(r.getInt(2)).isZero();assertThat(r.getBoolean(3)).isTrue();assertThat(r.getString(4)).isNull();assertThat(r.getObject(5)).isNull();}
            try(var r=s.executeQuery("SELECT password_hash,token_version FROM app_users WHERE id=100")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("preserved-test-hash");assertThat(r.getInt(2)).isEqualTo(7);}
            try(var r=s.executeQuery("SELECT o.customer_id,o.version,o.note,i.dish_name,i.unit_price,i.quantity FROM food_orders o JOIN order_items i ON i.order_id=o.id WHERE o.id=100")){assertThat(r.next()).isTrue();assertThat(r.getObject(1)).isNull();assertThat(r.getLong(2)).isEqualTo(4);assertThat(r.getString(3)).isEqualTo("Legacy note");assertThat(r.getString(4)).isEqualTo("Original name");assertThat(r.getInt(5)).isEqualTo(50000);assertThat(r.getInt(6)).isEqualTo(2);}
            try(var r=s.executeQuery("SELECT total,discount_amount,cashier FROM invoices WHERE id=100")){assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(90000);assertThat(r.getInt(2)).isEqualTo(10000);assertThat(r.getString(3)).isEqualTo("Original cashier");}
            try(var r=s.executeQuery("SELECT image_public_id FROM dishes WHERE id=100")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("preserved-photo-id");}
            s.executeUpdate("INSERT INTO member_favorites(user_id,dish_id,created_at) VALUES(101,100,now())");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO member_favorites(user_id,dish_id,created_at) VALUES(101,100,now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO member_favorites(user_id,dish_id,created_at) VALUES(99999,100,now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("UPDATE food_orders SET customer_id=99999 WHERE id=100")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("UPDATE app_users SET phone='invalid' WHERE id=101")).isInstanceOf(SQLException.class);
            s.executeUpdate("UPDATE app_users SET phone='+84901234567' WHERE id=101");
            s.executeUpdate("INSERT INTO member_point_entries(user_id,invoice_id,reference_key,points,reason,created_at) VALUES(101,100,'invoice-100',9,'Test only',now())");
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO member_point_entries(user_id,invoice_id,reference_key,points,reason,created_at) VALUES(101,100,'invoice-100',9,'Duplicate',now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("INSERT INTO member_point_entries(user_id,reference_key,points,reason,created_at) VALUES(101,'zero',0,'Invalid',now())")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.executeUpdate("DELETE FROM app_users WHERE id=101")).isInstanceOf(SQLException.class);
            try(var r=s.executeQuery("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_orders_customer_created','idx_member_favorites_created','idx_member_points_created')")){assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(3);}
            assertThat(Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().validateWithResult().validationSuccessful).isTrue();
        }
    }
}
