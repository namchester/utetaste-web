package vn.utetaste;

import java.math.BigDecimal;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
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

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class AdminPhaseOneIntegrationTest {
    @Autowired AdminService admin;@Autowired AdminCatalog catalog;@Autowired AppUserRepository users;
    @Autowired DishRepository dishes;@Autowired CategoryRepository categories;@Autowired AuditLogRepository audit;
    @Autowired JwtService jwt;@Autowired MockMvc mvc;
    @Autowired AdminQueries queries;@Autowired OrderService ops;@Autowired BranchRepository branches;
    @Autowired RestaurantTableRepository tables;@Autowired InvoiceRepository invoices;
    Actor actor(String name){var u=users.findByUsername(name).orElseThrow();return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    Cookie cookie(String name){return new Cookie("UT_ACCESS",jwt.staff(users.findByUsername(name).orElseThrow()));}
    Category empty(){var c=new Category();c.setName("Category-"+UUID.randomUUID());return categories.saveAndFlush(c);}
    Dish dish(Category c,String name,int price){return admin.dish(actor("admin"),new AdminService.DishForm(null,name,BigDecimal.valueOf(price),"Món kiểm thử",c.getId(),true));}
    @Test void emptyCategoryDeletionIsAuditedAndDoesNotDeleteDishes(){var c=empty();long count=dishes.count();admin.deleteCategory(actor("admin"),c.getId());assertThat(categories.existsById(c.getId())).isFalse();assertThat(dishes.count()).isEqualTo(count);assertThat(audit.findTop100ByOrderByCreatedAtDesc()).anySatisfy(l->{assertThat(l.getAction()).isEqualTo("DELETE_CATEGORY");assertThat(l.getDetail()).contains("#"+c.getId());});}
    @Test void linkedCategoryIncludingInactiveDishCannotBeDeleted(){var c=empty();var d=dish(c,"Linked",1000);admin.dishStates(actor("admin"),List.of(d.getId()),false);assertThatThrownBy(()->admin.deleteCategory(actor("admin"),c.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("1 món");assertThat(categories.existsById(c.getId())).isTrue();assertThat(dishes.existsById(d.getId())).isTrue();}
    @Test void moveDishThenDeleteCategoryKeepsDish(){var c=empty();var target=empty();var d=dish(c,"Move",2000);admin.dish(actor("admin"),new AdminService.DishForm(d.getId(),d.getName(),d.getPrice(),d.getDescription(),target.getId(),true));admin.deleteCategory(actor("admin"),c.getId());assertThat(dishes.findById(d.getId()).orElseThrow().getCategory().getId()).isEqualTo(target.getId());}
    @Test void menuFiltersSortAndPagingUseDatabase(){var c=empty();String prefix="Filter-"+UUID.randomUUID();var a=dish(c,prefix+" A",2000);var b=dish(c,prefix+" B",1000);var result=catalog.menu(actor("admin"),prefix,c.getId(),"active",null,"all","price",0,10);assertThat(result.getContent()).extracting(Dish::getId).containsExactly(b.getId(),a.getId());admin.dishStates(actor("admin"),List.of(a.getId()),false);assertThat(catalog.menu(actor("admin"),prefix,c.getId(),"inactive",null,"all","name",0,10).getContent()).extracting(Dish::getId).containsExactly(a.getId());for(int i=0;i<10;i++)dish(c,prefix+" extra "+i,1000);assertThat(catalog.menu(actor("admin"),prefix,c.getId(),"all",null,"all","name",0,10).getTotalElements()).isEqualTo(12);assertThat(catalog.menu(actor("admin"),prefix,c.getId(),"all",null,"all","name",1,10).getNumberOfElements()).isEqualTo(2);}
    @Test void bulkUpdateIsAtomicAndRejectsInvalidSelection(){var c=empty();var d=dish(c,"Atomic",1000);assertThatThrownBy(()->admin.dishStates(actor("admin"),List.of(d.getId(),Long.MAX_VALUE),false)).isInstanceOf(BusinessException.class);assertThat(dishes.findById(d.getId()).orElseThrow().isActive()).isTrue();assertThatThrownBy(()->admin.dishStates(actor("admin"),List.of(),false)).isInstanceOf(BusinessException.class);}
    @Test void serverRejectsInvalidFilterAndCategoryOrder(){assertThatThrownBy(()->catalog.menu(actor("admin"),"",null,"all",null,"all","unsafe",0,10)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->catalog.menu(actor("admin"),"",null,"all",null,"yes","name",0,10)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->admin.category(actor("admin"),null,"Invalid",-1)).isInstanceOf(BusinessException.class);}
    @Test void categorySearchCountsAllLinkedDishes(){var c=empty();dish(c,"Count",1000);var result=catalog.categories(actor("admin"),c.getName());assertThat(result).hasSize(1);assertThat(result.getFirst().dishCount()).isEqualTo(1);}
    @Test void menuRendersAndMutationsRequireAdminAndCsrf() throws Exception {var c=empty();mvc.perform(get("/admin/menu").cookie(cookie("admin"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("dish-bulk")));mvc.perform(post("/admin/categories/"+c.getId()+"/delete").cookie(cookie("admin"))).andExpect(status().isForbidden());mvc.perform(post("/admin/categories/"+c.getId()+"/delete").cookie(cookie("manager")).with(csrf())).andExpect(status().isForbidden());assertThatThrownBy(()->admin.deleteCategory(actor("waiter"),c.getId())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);mvc.perform(post("/admin/categories/"+c.getId()+"/delete").cookie(cookie("admin")).with(csrf())).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));}
    Branch branch(){return admin.branch(actor("admin"),new AdminService.BranchForm(null,"Branch-"+UUID.randomUUID(),"Địa chỉ test","0900000000",true));}
    Actor guest(Branch b){var t=new RestaurantTable();t.setBranch(b);t.setLabel("Table-"+UUID.randomUUID().toString().substring(0,8));t.setCapacity(4);tables.saveAndFlush(t);var s=ops.openTable(actor("admin"),t.getId());return new Actor(null,"guest:"+s.getId(),t.getLabel(),Role.GUEST,b.getId(),s.getId());}
    OrderService.OrderView place(Actor g,Dish d){return ops.place(g,new OrderService.PlaceRequest(UUID.randomUUID().toString(),List.of(new OrderService.Line(d.getId(),2)),"Ít cay"));}
    void serve(Long id){for(var s:List.of(OrderStatus.CONFIRMED,OrderStatus.COOKING,OrderStatus.READY,OrderStatus.SERVED))ops.changeStatus(actor("admin"),id,s);}
    AdminQueries.Period today(){var now=LocalDate.now(Reports.ZONE);return AdminQueries.period(now,now);}
    @Test void dashboardReconcilesPaidInvoicesAndExcludesUnpaidOrCancelled() {
        var b=branch();var d=dish(empty(),"Snapshot",10500);var g=guest(b);var paid=place(g,d);serve(paid.id());var invoice=ops.checkout(actor("admin"),g.sessionId(),10,PaymentMethod.CASH);
        var unpaid=place(guest(b),d);serve(unpaid.id());var cancelled=place(guest(b),d);ops.changeStatus(actor("admin"),cancelled.id(),OrderStatus.CANCELLED);
        admin.user(actor("admin"),new AdminService.UserForm(null,"employee_"+UUID.randomUUID(),"Employee",Role.MANAGER,b.getId(),"Employee@Test2026",true));
        var stats=queries.dashboard(actor("admin"),b.getId(),today());
        assertThat(stats.periodRevenue()).isEqualByComparingTo(invoice.getTotal());assertThat(stats.today()).isEqualByComparingTo(invoice.getTotal());assertThat(stats.week()).isEqualByComparingTo(invoice.getTotal());assertThat(stats.month()).isEqualByComparingTo(invoice.getTotal());
        assertThat(stats.invoiceCount()).isEqualTo(1);assertThat(stats.tableCount()).isEqualTo(3);assertThat(stats.openSessions()).isEqualTo(2);assertThat(stats.occupied()).isEqualTo(2);assertThat(stats.free()).isEqualTo(1);assertThat(stats.staff()).isEqualTo(1);
        assertThat(stats.days7()).hasSize(7);assertThat(stats.days30()).hasSize(30);assertThat(stats.days30().getLast().amount()).isEqualByComparingTo(invoice.getTotal());
        assertThat(stats.top()).hasSize(1);assertThat(stats.top().getFirst().quantity()).isEqualTo(2);assertThat(stats.top().getFirst().revenue()).isEqualByComparingTo("21000");
        assertThat(stats.branchRevenue().getFirst().revenue()).isEqualByComparingTo(invoice.getTotal());assertThat(stats.statuses().stream().filter(x->x.status()==OrderStatus.CANCELLED).findFirst().orElseThrow().count()).isEqualTo(1);
    }
    @Test void orderHistorySearchAndSnapshotRemainCorrectAfterMenuEdit() throws Exception {
        var b=branch();var d=dish(empty(),"Original-name",5000);var g=guest(b);var o=place(g,d);serve(o.id());var invoice=ops.checkout(actor("admin"),g.sessionId(),0,PaymentMethod.BANK_TRANSFER);
        admin.dish(actor("admin"),new AdminService.DishForm(d.getId(),"Renamed",BigDecimal.valueOf(99999),"New",d.getCategory().getId(),true));
        var detail=queries.detail(actor("admin"),o.id());assertThat(detail.row().paid()).isTrue();assertThat(detail.row().sessionOpen()).isFalse();assertThat(detail.invoiceId()).isEqualTo(invoice.getId());assertThat(detail.history()).hasSize(5);
        assertThat(detail.row().order().items().getFirst().name()).isEqualTo("Original-name");assertThat(detail.row().order().items().getFirst().price()).isEqualByComparingTo("5000");
        assertThat(queries.searchOrders(actor("admin"),o.code(),null,null,today(),0,10).page().getTotalElements()).isEqualTo(1);
        assertThat(queries.searchOrders(actor("admin"),b.getName(),b.getId(),OrderStatus.SERVED,today(),0,10).page().getTotalElements()).isEqualTo(1);
        assertThat(queries.searchOrders(actor("admin"),o.code(),actor("waiter").branchId(),null,today(),0,10).page()).isEmpty();
        mvc.perform(get("/admin/orders/"+o.id()).cookie(cookie("admin"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Original-name")));
        mvc.perform(post("/api/staff/orders/"+o.id()+"/status").cookie(cookie("admin")).with(csrf()).contentType("application/json").content("{\"status\":\"CANCELLED\"}")).andExpect(status().isBadRequest());
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getTotal()).isEqualByComparingTo("10000");
    }
    @Test void orderPaginationAndDateFiltersWorkOnDatabase() {
        var b=branch();var g=guest(b);var d=dish(empty(),"Paging",1000);for(int i=0;i<12;i++)place(g,d);
        var first=queries.searchOrders(actor("admin"),"",b.getId(),OrderStatus.NEW,today(),0,10).page();assertThat(first.getTotalElements()).isEqualTo(12);assertThat(first.getNumberOfElements()).isEqualTo(10);
        assertThat(queries.searchOrders(actor("admin"),g.displayName(),b.getId(),null,today(),1,10).page().getNumberOfElements()).isEqualTo(2);
        var future=AdminQueries.period(today().from().plusDays(1),today().to().plusDays(1));assertThat(queries.searchOrders(actor("admin"),"",b.getId(),null,future,0,10).page()).isEmpty();
        assertThatThrownBy(()->AdminQueries.period(today().from().minusDays(366),today().to())).isInstanceOf(BusinessException.class);
    }
    @Test void adminDashboardAndOrderPagesEnforceBackendRoles() throws Exception {
        mvc.perform(get("/staff").cookie(cookie("admin"))).andExpect(status().isOk()).andExpect(view().name("admin-dashboard"));
        mvc.perform(get("/admin/orders").cookie(cookie("admin"))).andExpect(status().isOk());
        for(String name:List.of("manager","waiter","chef"))mvc.perform(get("/admin/orders").cookie(cookie(name))).andExpect(status().isForbidden());
        assertThatThrownBy(()->queries.dashboard(actor("manager"),actor("manager").branchId(),today())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        mvc.perform(get("/staff").cookie(cookie("waiter"))).andExpect(status().isOk()).andExpect(view().name("dashboard"));
    }
    @Test void concurrentCategoryDeleteAndDishCreationCannotLeaveOrphans() throws Exception {
        var c=empty();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var deleting=pool.submit(()->{start.await();try{admin.deleteCategory(actor("admin"),c.getId());return true;}catch(BusinessException|org.springframework.dao.DataIntegrityViolationException e){return false;}});
            var creating=pool.submit(()->{start.await();try{dish(c,"Concurrent",1000);return true;}catch(BusinessException|org.springframework.dao.DataIntegrityViolationException e){return false;}});
            start.countDown();boolean deleted=deleting.get(15,TimeUnit.SECONDS),created=creating.get(15,TimeUnit.SECONDS);
            assertThat(deleted).isNotEqualTo(created);assertThat(categories.existsById(c.getId())).isEqualTo(created);assertThat(dishes.countByCategoryId(c.getId())).isEqualTo(created?1:0);
        }finally{pool.shutdownNow();}
    }
    @Test void menuSearchTreatsPercentAndUnderscoreAsLiteralCharacters(){var c=empty();var exact=dish(c,"Special 100%_meal",1000);dish(c,"Special 100XYmeal",1000);assertThat(catalog.menu(actor("admin"),"100%_",c.getId(),"all",null,"all","name",0,10).getContent()).extracting(Dish::getId).containsExactly(exact.getId());}
    @Test void dashboardRevenueUsesVietnamDayBoundaries() {
        var b=branch();var d=dish(empty(),"Timezone",1000);var day=today().from();
        var boundaries=List.of(day.atStartOfDay(Reports.ZONE).toOffsetDateTime().minusSeconds(1),day.atStartOfDay(Reports.ZONE).toOffsetDateTime(),day.plusDays(1).atStartOfDay(Reports.ZONE).toOffsetDateTime());
        for(var time:boundaries){var g=guest(b);var order=place(g,d);serve(order.id());var invoice=ops.checkout(actor("admin"),g.sessionId(),0,PaymentMethod.CASH);invoice.setPaidAt(time.withOffsetSameInstant(ZoneOffset.UTC));invoices.saveAndFlush(invoice);}
        var stats=queries.dashboard(actor("admin"),b.getId(),today());assertThat(stats.invoiceCount()).isEqualTo(1);assertThat(stats.periodRevenue()).isEqualByComparingTo("2000");assertThat(stats.days7().getLast().amount()).isEqualByComparingTo("2000");
        var all=queries.dashboard(actor("admin"),null,today());assertThat(all.branchRevenue().stream().map(AdminQueries.BranchRevenue::revenue).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(all.periodRevenue());
    }
    @Test void dashboardPreservesServiceCallsAndTheirBranchScope(){var a=branch();var b=branch();var g=guest(a);ops.call(g,"Cần hỗ trợ");ops.call(guest(b),"Chi nhánh khác");var stats=queries.dashboard(actor("admin"),a.getId(),today());assertThat(stats.callCount()).isEqualTo(1);assertThat(stats.calls()).hasSize(1);assertThat(stats.calls().getFirst().getMessage()).isEqualTo("Cần hỗ trợ");ops.resolveCall(actor("admin"),stats.calls().getFirst().getId());assertThat(queries.dashboard(actor("admin"),a.getId(),today()).callCount()).isZero();}
}
