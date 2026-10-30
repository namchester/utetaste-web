package vn.utetaste;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc @ActiveProfiles("test")
class UtetasteIntegrationTest {
    @Autowired MockMvc mvc;@Autowired OrderService ops;@Autowired JwtService jwt;
    @Autowired AppUserRepository users;@Autowired BranchRepository branches;@Autowired DishRepository dishes;@Autowired BranchDishRepository availability;
    @Autowired RestaurantTableRepository tables;@Autowired DiningSessionRepository sessions;@Autowired InvoiceRepository invoices;@Autowired FoodOrderRepository orders;
    @LocalServerPort int port;
    Actor admin,waiter,chef,guest;DiningSession session;Dish dish;
    Actor actor(String username){var u=users.findByUsername(username).orElseThrow();return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    Cookie cookie(String username){return new Cookie("UT_ACCESS",jwt.staff(users.findByUsername(username).orElseThrow()));}
    @BeforeEach void prepare(){admin=actor("admin");waiter=actor("waiter");chef=actor("chef");dish=dishes.findAllByOrderByNameAsc().get(0);var t=new RestaurantTable();t.setBranch(branches.findById(waiter.branchId()).orElseThrow());t.setLabel("Test-"+UUID.randomUUID().toString().substring(0,12));t.setCapacity(4);tables.save(t);session=ops.openTable(waiter,t.getId());guest=new Actor(null,"guest:"+session.getId(),t.getLabel(),Role.GUEST,waiter.branchId(),session.getId());}
    OrderService.PlaceRequest request(String key,int quantity){return new OrderService.PlaceRequest(key,List.of(new OrderService.Line(dish.getId(),quantity)),"Ít cay");}
    OrderService.OrderView order(){return ops.place(guest,request(UUID.randomUUID().toString(),2));}
    void served(Long id){ops.changeStatus(waiter,id,OrderStatus.CONFIRMED);ops.changeStatus(chef,id,OrderStatus.COOKING);ops.changeStatus(chef,id,OrderStatus.READY);ops.changeStatus(waiter,id,OrderStatus.SERVED);}
    @Test void completeWorkflowAndInvoiceAreConsistent(){var o=order();assertThat(o.total()).isEqualByComparingTo(dish.getPrice().multiply(BigDecimal.valueOf(2)));served(o.id());var invoice=ops.checkout(admin,session.getId(),10,PaymentMethod.CASH);assertThat(invoice.getTotal()).isEqualByComparingTo(o.total().multiply(new BigDecimal("0.90")));assertThat(sessions.findById(session.getId()).orElseThrow().isOpen()).isFalse();assertThat(ops.checkout(admin,session.getId(),10,PaymentMethod.CASH).getId()).isEqualTo(invoice.getId());assertThatThrownBy(()->order()).isInstanceOf(BusinessException.class);}
    @Test void repeatedSubmitCreatesOneOrder(){var key=UUID.randomUUID().toString();var a=ops.place(guest,request(key,2));var b=ops.place(guest,request(key,2));assertThat(b.id()).isEqualTo(a.id());assertThat(ops.guestOrders(guest)).hasSize(1);}
    @Test void concurrentDuplicateSubmissionCreatesOneOrder() throws Exception {var key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->ops.place(guest,request(key,2)));var b=pool.submit(()->ops.place(guest,request(key,2)));assertThat(a.get(15,TimeUnit.SECONDS).id()).isEqualTo(b.get(15,TimeUnit.SECONDS).id());assertThat(ops.guestOrders(guest)).hasSize(1);}finally{pool.shutdownNow();}}
    @Test void priceIsSnapshotted(){var original=dish.getPrice();var o=order();try{dish.setPrice(original.add(BigDecimal.valueOf(10000)));dishes.saveAndFlush(dish);assertThat(ops.guestOrders(guest).get(0).total()).isEqualByComparingTo(o.total());}finally{dish.setPrice(original);dishes.saveAndFlush(dish);}}
    @Test void branchIsolationAndRoleTransitions(){var o=order();assertThatThrownBy(()->ops.changeStatus(actor("waiter2"),o.id(),OrderStatus.CONFIRMED)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->ops.changeStatus(chef,o.id(),OrderStatus.CONFIRMED)).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->ops.changeStatus(waiter,o.id(),OrderStatus.SERVED)).isInstanceOf(BusinessException.class);ops.changeStatus(waiter,o.id(),OrderStatus.CONFIRMED);assertThatThrownBy(()->ops.changeStatus(waiter,o.id(),OrderStatus.COOKING)).isInstanceOf(AccessDeniedException.class);}
    @Test void checkoutRequiresServedOrdersAndAuthorizedDiscount(){var o=order();assertThatThrownBy(()->ops.checkout(waiter,session.getId(),0,PaymentMethod.CASH)).isInstanceOf(BusinessException.class);served(o.id());assertThatThrownBy(()->ops.checkout(waiter,session.getId(),10,PaymentMethod.CASH)).isInstanceOf(AccessDeniedException.class);}
    @Test void unavailableFoodAndInvalidQuantityAreRejected(){var bd=availability.findByBranchIdAndDishId(waiter.branchId(),dish.getId()).orElseThrow();try{bd.setAvailable(false);availability.saveAndFlush(bd);assertThatThrownBy(()->order()).isInstanceOf(BusinessException.class);}finally{bd.setAvailable(true);availability.saveAndFlush(bd);}assertThatThrownBy(()->ops.place(guest,request(UUID.randomUUID().toString(),-1))).isInstanceOf(BusinessException.class);}
    @Test void cannotCloseUnpaidTableAndCanCloseCancelled(){var o=order();assertThatThrownBy(()->ops.closeEmpty(waiter,session.getId())).isInstanceOf(BusinessException.class);ops.changeStatus(waiter,o.id(),OrderStatus.CANCELLED);ops.closeEmpty(waiter,session.getId());assertThat(sessions.findById(session.getId()).orElseThrow().isOpen()).isFalse();}
    @Test void templatesRenderForRealData() throws Exception {
        var o=order();served(o.id());var invoice=ops.checkout(admin,session.getId(),0,PaymentMethod.CASH);
        for(String path:List.of("/","/login","/staff","/staff/tables","/staff/orders","/staff/kitchen","/staff/menu","/staff/reports","/admin/branches","/admin/branches?edit="+waiter.branchId(),"/admin/menu","/admin/menu?edit="+dish.getId(),"/admin/users","/admin/users?edit="+waiter.id(),"/admin/audit","/account/password","/staff/sessions/"+session.getId(),"/staff/receipt/"+invoice.getId(),"/staff/tables/"+session.getTable().getId()+"/qr")) mvc.perform(get(path).cookie(cookie("admin"))).andExpect(status().isOk());
        mvc.perform(get("/guest/menu").cookie(new Cookie("UT_GUEST",jwt.guest(session)))).andExpect(status().isOk());mvc.perform(get("/guest/orders").cookie(new Cookie("UT_GUEST",jwt.guest(session)))).andExpect(status().isOk());
        var open=ops.openTable(waiter,session.getTable().getId());mvc.perform(get("/t/"+open.getTable().getQrKey())).andExpect(status().isOk());
        mvc.perform(get("/staff/tables/"+session.getTable().getId()+"/qr.png").cookie(cookie("admin"))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
    }
    @Test void csrfJwtTamperAndCrossBranchHttpAreBlocked() throws Exception {
        mvc.perform(post("/staff/tables/"+session.getTable().getId()+"/open").cookie(cookie("waiter"))).andExpect(status().isForbidden());
        mvc.perform(get("/staff").cookie(new Cookie("UT_ACCESS","tampered.jwt.signature"))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/users").cookie(cookie("waiter"))).andExpect(status().isForbidden());
        mvc.perform(get("/staff/sessions/"+session.getId()).cookie(cookie("waiter2"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/guest/orders").with(csrf()).cookie(new Cookie("UT_GUEST",jwt.guest(session))).contentType("application/json").content("{\"requestKey\":\""+UUID.randomUUID()+"\",\"items\":[{\"dishId\":"+dish.getId()+",\"quantity\":-3}]}")).andExpect(status().isBadRequest());
    }
    @Test void loginAndLogoutUseHttpOnlyCookiesAndRevokeToken() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username","manager2").param("password","Utetaste@Test2026")).andExpect(status().is3xxRedirection()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("HttpOnly")));
        var c=cookie("manager2");mvc.perform(post("/logout").with(csrf()).cookie(c)).andExpect(status().is3xxRedirection());mvc.perform(get("/staff").cookie(c)).andExpect(status().is3xxRedirection());
    }
    @Test void guestCannotReadAnotherSession() throws Exception {var another=ops.openTable(waiter,tables.findByBranchIdOrderByLabel(waiter.branchId()).get(0).getId());var other=new Actor(null,"guest:"+another.getId(),"guest",Role.GUEST,waiter.branchId(),another.getId());ops.place(other,request(UUID.randomUUID().toString(),1));assertThat(ops.guestOrders(guest)).isEmpty();mvc.perform(get("/staff/sessions/"+another.getId()).cookie(new Cookie("UT_GUEST",jwt.guest(session)))).andExpect(status().is3xxRedirection());}
    @Test void webSocketReceivesCommittedEvents() throws Exception {
        var received=new CompletableFuture<String>();
        var ws=HttpClient.newHttpClient().newWebSocketBuilder().header("Cookie","UT_ACCESS="+jwt.staff(users.findByUsername("chef").orElseThrow())).buildAsync(URI.create("ws://localhost:"+port+"/ws/staff"),new WebSocket.Listener(){public void onOpen(WebSocket w){w.request(1);}public CompletionStage<?> onText(WebSocket w,CharSequence data,boolean last){if(data.toString().contains("orders.changed"))received.complete(data.toString());w.request(1);return null;}}).get(10,TimeUnit.SECONDS);
        try{order();assertThat(received.get(10,TimeUnit.SECONDS)).contains("orders.changed");}finally{ws.sendClose(WebSocket.NORMAL_CLOSURE,"test complete").get(5,TimeUnit.SECONDS);}
    }
    @Test void adminFormsCreateAndEditCatalogAndUsers() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        mvc.perform(post("/admin/branches").with(csrf()).cookie(cookie("admin")).param("name","Chi nhánh test "+suffix).param("address","Địa chỉ test").param("phone","0901234567").param("active","true")).andExpect(status().is3xxRedirection());
        var created=branches.findAll().stream().filter(b->b.getName().endsWith(suffix)).findFirst().orElseThrow();
        mvc.perform(post("/admin/menu").with(csrf()).cookie(cookie("admin")).param("name","Món test "+suffix).param("price","55000").param("description","Món kiểm thử").param("categoryId",dish.getCategory().getId().toString()).param("active","true")).andExpect(status().is3xxRedirection());
        var added=dishes.findAll().stream().filter(d->d.getName().endsWith(suffix)).findFirst().orElseThrow();
        assertThat(availability.findByBranchIdAndDishId(created.getId(),added.getId())).isPresent();
        mvc.perform(post("/admin/users").with(csrf()).cookie(cookie("admin")).param("username","test"+suffix).param("fullName","Nhân viên test").param("role","WAITER").param("branchId",created.getId().toString()).param("password","TestPassword@2026").param("active","true")).andExpect(status().is3xxRedirection());
        assertThat(users.findByUsername("test"+suffix)).isPresent();
        mvc.perform(post("/staff/tables/save").with(csrf()).cookie(cookie("admin")).param("branchId",created.getId().toString()).param("label","Bàn mới").param("capacity","4").param("active","true")).andExpect(status().is3xxRedirection());
        assertThat(tables.findByBranchIdOrderByLabel(created.getId())).hasSize(1);
    }
    @Test void guestJoinIssuesSeparateHttpOnlyCookie() throws Exception {
        mvc.perform(post("/t/"+session.getTable().getQrKey()+"/join").with(csrf())).andExpect(status().is3xxRedirection()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("UT_GUEST="),org.hamcrest.Matchers.containsString("HttpOnly"))));
    }
}
