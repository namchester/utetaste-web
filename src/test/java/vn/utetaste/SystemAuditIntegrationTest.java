package vn.utetaste;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.servlet.http.Cookie;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties="spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test") @AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SystemAuditIntegrationTest {
    @Autowired OrderService ops;@Autowired AdminService admin;@Autowired AppUserRepository users;
    @Autowired BranchRepository branches;@Autowired RestaurantTableRepository tables;@Autowired DiningSessionRepository sessions;
    @Autowired DishRepository dishes;@Autowired FoodOrderRepository orders;@Autowired PlatformTransactionManager tx;
    @Autowired EntityManagerFactory emf;@Autowired MockMvc mvc;@Autowired JwtService jwt;
    Actor actor(String name){var u=users.findByUsername(name).orElseThrow();return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    Cookie cookie(){return new Cookie("UT_ACCESS",jwt.staff(users.findByUsername("admin").orElseThrow()));}
    Branch branch(){return admin.branch(actor("admin"),new AdminService.BranchForm(null,"Audit-"+UUID.randomUUID(),"Địa chỉ thử","0900000000",true));}
    DiningSession session(Branch b){var t=new RestaurantTable();t.setBranch(b);t.setLabel("Audit-"+UUID.randomUUID().toString().substring(0,8));t.setCapacity(4);tables.saveAndFlush(t);return ops.openTable(actor("admin"),t.getId());}
    Actor guest(DiningSession s){return new Actor(null,"guest:"+s.getId(),s.getTable().getLabel(),Role.GUEST,s.getTable().getBranch().getId(),s.getId());}
    OrderService.PlaceRequest request(){return new OrderService.PlaceRequest(UUID.randomUUID().toString(),List.of(new OrderService.Line(dishes.findAllByOrderByNameAsc().getFirst().getId(),2)),"");}
    void serve(Long id){for(var status:List.of(OrderStatus.CONFIRMED,OrderStatus.COOKING,OrderStatus.READY,OrderStatus.SERVED))ops.changeStatus(actor("admin"),id,status);}
    void staleRequest(DiningSession s,Runnable close,Runnable action) throws Exception {
        var loaded=new CountDownLatch(1);var resume=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try {
            var request=pool.submit(()->assertThatThrownBy(()->new TransactionTemplate(tx).executeWithoutResult(status->{
                assertThat(sessions.findById(s.getId()).orElseThrow().isOpen()).isTrue();loaded.countDown();
                try{assertThat(resume.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                action.run();
            })).isInstanceOf(BusinessException.class));
            assertThat(loaded.await(15,TimeUnit.SECONDS)).isTrue();close.run();resume.countDown();request.get(20,TimeUnit.SECONDS);
        } finally {resume.countDown();pool.shutdownNow();}
    }
    @Test void inFlightGuestCannotAppendOrderAfterCheckout() throws Exception {
        var s=session(branch());var g=guest(s);var o=ops.place(g,request());serve(o.id());var next=request();
        staleRequest(s,()->ops.checkout(actor("admin"),s.getId(),0,PaymentMethod.CASH),()->ops.place(g,next));
        assertThat(orders.findBySessionIdOrderByCreatedAt(s.getId())).hasSize(1);
    }
    @Test void inFlightGuestCannotCreateServiceCallAfterClosing() throws Exception {
        var s=session(branch());var g=guest(s);
        staleRequest(s,()->ops.closeEmpty(actor("admin"),s.getId()),()->ops.call(g,"Cần thanh toán"));
    }
    @Test void tableOverviewUsesBoundedQueriesAndPreservesTotals() {
        var b=branch();DiningSession first=null;
        for(int n=0;n<10;n++){var s=session(b);if(first==null)first=s;}
        var o=ops.place(guest(first),request());var cancelled=ops.place(guest(first),request());ops.changeStatus(actor("admin"),cancelled.id(),OrderStatus.CANCELLED);
        var a=actor("admin");var stats=emf.unwrap(SessionFactory.class).getStatistics();stats.clear();
        var result=ops.tableViews(a,b.getId());long queries=stats.getPrepareStatementCount();
        assertThat(result).hasSize(10);assertThat(result.stream().map(OrderService.TableView::total).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(o.total());
        assertThat(result.stream().mapToInt(OrderService.TableView::orderCount).sum()).isEqualTo(1);
        System.out.println("AUDIT_TABLE_SQL ten_open_tables="+queries);
        assertThat(queries).as("Table list SQL statements for ten open tables").isLessThanOrEqualTo(5);
    }
    @Test void malformedRequestsHaveSafeVietnameseMessages() throws Exception {
        mvc.perform(get("/admin/menu").param("page","invalid-private-detail").cookie(cookie())).andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.containsString("Dữ liệu yêu cầu không hợp lệ"))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("invalid-private-detail"))));
        mvc.perform(post("/api/staff/orders/1/status").cookie(cookie()).with(csrf()).contentType("application/json").content("{broken-secret-value"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Dữ liệu yêu cầu không hợp lệ. Hãy kiểm tra kiểu dữ liệu và các trường bắt buộc."));
    }
}
