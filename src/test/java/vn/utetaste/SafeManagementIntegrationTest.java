package vn.utetaste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Real services and constraints; each fixture commits before independent removal transactions. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class SafeManagementIntegrationTest {
    @Autowired AdminService admin; @Autowired AdminRemovalService removal; @Autowired OrderService ops;
    @Autowired MemberService member; @Autowired Reports reports;
    @Autowired BranchRepository branches; @Autowired DishRepository dishes; @Autowired CategoryRepository categories;
    @Autowired BranchDishRepository menu; @Autowired RestaurantTableRepository tables;
    @Autowired AppUserRepository users; @Autowired MemberFavoriteRepository favorites;
    @Autowired AuditLogRepository audit; @Autowired FoodOrderRepository orders; @Autowired InvoiceRepository invoices;
    @Autowired JwtService jwt; @Autowired MockMvc mvc; @Autowired PlatformTransactionManager transactions;

    Actor actor(AppUser u) {return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    Actor actor(String name) {return actor(users.findByUsername(name).orElseThrow());}
    Actor admin() {return actor("admin");}
    Cookie cookie(Actor a) {return new Cookie("UT_ACCESS",jwt.staff(users.findById(a.id()).orElseThrow()));}
    String unique() {return UUID.randomUUID().toString();}
    AdminService.BranchForm form(String name) {return new AdminService.BranchForm(null,name,"Địa chỉ kiểm thử","0900000000",true);}
    Branch branch() {return admin.branch(admin(),form("CRUD-"+unique()));}
    Dish dish() {
        var c=new Category();c.setName("CRUD-"+unique());categories.saveAndFlush(c);
        return admin.dish(admin(),new AdminService.DishForm(null,"Món-"+unique(),BigDecimal.valueOf(10000),"Mô tả kiểm thử",c.getId(),true));
    }
    RestaurantTable table(Branch b) {var t=new RestaurantTable();t.setBranch(b);t.setLabel("T-"+unique().substring(0,8));t.setCapacity(4);return tables.saveAndFlush(t);}
    Actor guest(Branch b) {var t=table(b);var session=ops.openTable(admin(),t.getId());return new Actor(null,"guest:"+session.getId(),t.getLabel(),Role.GUEST,b.getId(),session.getId());}
    OrderService.OrderView place(Actor g,Dish d) {return ops.place(g,new OrderService.PlaceRequest(unique(),List.of(new OrderService.Line(d.getId(),2)),"CRUD"));}
    AppUser customer() {
        var u=new AppUser();u.setUsername("crud_"+unique());u.setFullName("Khách thử");u.setRole(Role.USER);
        u.setEmail(unique()+"@example.test");u.setEmailVerified(true);u.setPasswordHash("test-only-unused-hash");
        return users.saveAndFlush(u);
    }
    long deletes(String action,Long id) {return audit.findAll().stream().filter(l->l.getAction().equals(action)&&l.getDetail().startsWith((action.equals("DELETE_BRANCH")?"Chi nhánh #":"Món #")+id+" • ")).count();}
    List<Boolean> concurrent(Supplier<Boolean> first,Supplier<Boolean> second) throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{start.await();return first.get();});
            var two=pool.submit(()->{start.await();return second.get();});
            start.countDown();return List.of(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }

    @Test void createBranchIsIdempotentAndEditChangesOnlyItsId() {
        var input=form("CRUD-"+unique());var b=admin.branch(admin(),input);
        assertThat(admin.branch(admin(),input).getId()).isEqualTo(b.getId());
        assertThat(branches.findByNameIgnoreCase(input.name())).hasSize(1);
        var other=branch();var changed=admin.branch(admin(),new AdminService.BranchForm(b.getId(),input.name()+" sửa","Địa chỉ mới","0901234567",false));
        assertThat(changed.getId()).isEqualTo(b.getId());assertThat(branches.findById(b.getId()).orElseThrow().isActive()).isFalse();
        assertThat(branches.findById(other.getId()).orElseThrow().getName()).isEqualTo(other.getName());
        assertThatThrownBy(()->admin.branch(admin(),new AdminService.BranchForm(null,input.name()+" sửa","Khác","0900000000",true))).isInstanceOf(ManagementException.class);
    }
    @Test void concurrentRepeatedBranchAndDishCreationHaveOneRecord() throws Exception {
        var input=form("CRUD-"+unique());var a=admin();
        assertThat(concurrent(()->{admin.branch(a,input);return true;},()->{admin.branch(a,input);return true;})).containsOnly(true);
        assertThat(branches.findByNameIgnoreCase(input.name())).hasSize(1);
        var d=dish();var f=new AdminService.DishForm(null,d.getName()+" new",d.getPrice(),d.getDescription(),d.getCategory().getId(),true);
        concurrent(()->{admin.dish(a,f);return true;},()->{admin.dish(a,f);return true;});
        assertThat(dishes.findByNameIgnoreCaseAndCategoryId(f.name(),f.categoryId())).hasSize(1);
    }
    @Test void invalidBranchAndDishFormsRenderInputWithoutWriting() throws Exception {
        long before=branches.count();
        mvc.perform(post("/admin/branches").cookie(cookie(admin())).with(csrf()).param("name","").param("address","A").param("phone","invalid"))
            .andExpect(status().isBadRequest()).andExpect(view().name("admin-branches")).andExpect(model().attributeExists("error","editing"));
        assertThat(branches.count()).isEqualTo(before);
        mvc.perform(post("/admin/menu").cookie(cookie(admin())).with(csrf()).param("name","Món lỗi").param("description","").param("price","-1"))
            .andExpect(status().isBadRequest()).andExpect(view().name("admin-menu")).andExpect(model().attributeExists("error"));
        assertThatThrownBy(()->admin.branch(admin(),new AdminService.BranchForm(null,"A","B","abc",true))).isInstanceOf(BusinessException.class);
    }
    @Test void emptyBranchDeletionRemovesOnlySetupLinksAndKeepsAuditAndDishes() {
        var b=branch();var d=dish();assertThat(menu.findByBranchIdAndDishId(b.getId(),d.getId())).isPresent();
        removal.deleteBranch(admin(),b.getId());
        assertThat(branches.existsById(b.getId())).isFalse();assertThat(menu.findByBranchIdOrderByDishName(b.getId())).isEmpty();
        assertThat(dishes.existsById(d.getId())).isTrue();assertThat(deletes("DELETE_BRANCH",b.getId())).isEqualTo(1);
        assertThat(audit.findAll().stream().anyMatch(l->l.getAction().equals("SAVE_BRANCH")&&Objects.equals(l.getBranchId(),b.getId()))).isTrue();
        assertThatThrownBy(()->removal.deleteBranch(admin(),b.getId())).isInstanceOf(ManagementException.class);
    }
    @Test void branchWithStaffOrTablesOrHistoricalAuditCannotBeDeletedOrSilentlyStopped() {
        var staffed=branch();var u=new AppUser();u.setUsername("staff_"+unique());u.setFullName("Staff");u.setRole(Role.CHEF);u.setBranch(staffed);u.setPasswordHash("unused-test-only");users.saveAndFlush(u);
        var withTable=branch();table(withTable);var historical=branch();var l=new AuditLog();l.setActor("test");l.setAction("CHECKOUT");l.setDetail("History");l.setBranchId(historical.getId());audit.saveAndFlush(l);
        for(var b:List.of(staffed,withTable,historical)) {
            assertThatThrownBy(()->removal.deleteBranch(admin(),b.getId())).isInstanceOf(ManagementException.class);
            assertThat(branches.findById(b.getId()).orElseThrow().isActive()).isTrue();assertThat(deletes("DELETE_BRANCH",b.getId())).isZero();
        }
    }
    @Test void branchWithOpenOrClosedSessionAndOrderHistoryIsRetained() {
        var b=branch();var g=guest(b);var d=dish();var order=place(g,d);
        assertThatThrownBy(()->removal.deleteBranch(admin(),b.getId())).isInstanceOf(ManagementException.class);
        assertThatThrownBy(()->admin.branchState(admin(),b.getId(),false)).isInstanceOf(ManagementException.class);
        ops.changeStatus(admin(),order.id(),OrderStatus.CANCELLED);ops.closeEmpty(admin(),g.sessionId());admin.branchState(admin(),b.getId(),false);
        assertThatThrownBy(()->removal.deleteBranch(admin(),b.getId())).isInstanceOf(ManagementException.class);
        assertThat(orders.existsById(order.id())).isTrue();admin.branchState(admin(),b.getId(),true);
    }
    @Test void branchStopPreventsNewSessionAndCanBeReactivated() {
        var b=branch();var t=table(b);admin.branchState(admin(),b.getId(),false);
        assertThatThrownBy(()->ops.openTable(admin(),t.getId())).isInstanceOf(BusinessException.class);
        admin.branchState(admin(),b.getId(),true);assertThat(ops.openTable(admin(),t.getId()).isOpen()).isTrue();
    }
    @Test void concurrentStopAndOpenNeverLeaveInactiveBranchWithOpenSession() throws Exception {
        var b=branch();var t=table(b);var a=admin();
        var outcomes=concurrent(()->{try{admin.branchState(a,b.getId(),false);return true;}catch(BusinessException e){return false;}},
            ()->{try{ops.openTable(a,t.getId());return true;}catch(BusinessException e){return false;}});
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);
        assertThat(branches.findById(b.getId()).orElseThrow().isActive()).isEqualTo(outcomes.get(1));
    }
    @Test void dishAddEditAndRemovalKeepCategoryOtherRowsAndSharedImage() {
        var b=branch();var d=dish();var other=dish();
        for(var row:List.of(d,other)) {row.setImageUrl("https://res.cloudinary.com/test-only/image/upload/shared.webp");row.setImagePublicId("test-only/shared");dishes.saveAndFlush(row);}
        var updated=admin.dish(admin(),new AdminService.DishForm(d.getId(),d.getName()+" sửa",BigDecimal.valueOf(12000),"Mô tả mới",d.getCategory().getId(),true));
        assertThat(updated.getId()).isEqualTo(d.getId());assertThat(updated.getImagePublicId()).isEqualTo("test-only/shared");
        var result=removal.deleteDishes(admin(),List.of(d.getId()));
        assertThat(result.removedCount()).isEqualTo(1);assertThat(dishes.existsById(d.getId())).isFalse();
        assertThat(menu.findByBranchIdAndDishId(b.getId(),d.getId())).isEmpty();assertThat(categories.existsById(d.getCategory().getId())).isTrue();
        assertThat(dishes.findById(other.getId()).orElseThrow().getImagePublicId()).isEqualTo("test-only/shared");
        assertThat(deletes("DELETE_DISH",d.getId())).isEqualTo(1);
    }
    @Test void everyOrderStatusIncludingCancelledPreventsDishDeletion() {
        for(var status:List.of(OrderStatus.NEW,OrderStatus.CONFIRMED,OrderStatus.COOKING,OrderStatus.READY,OrderStatus.SERVED,OrderStatus.CANCELLED)) {
            var d=dish();var g=guest(branch());var order=place(g,d);
            if(status==OrderStatus.CANCELLED) ops.changeStatus(admin(),order.id(),status);
            else for(var next:List.of(OrderStatus.CONFIRMED,OrderStatus.COOKING,OrderStatus.READY,OrderStatus.SERVED)) {
                if(status==OrderStatus.NEW) break;ops.changeStatus(admin(),order.id(),next);if(next==status)break;
            }
            var result=removal.deleteDishes(admin(),List.of(d.getId()));
            assertThat(result.removedCount()).isZero();assertThat(result.items().getFirst().reason()).contains("lịch sử");
            assertThat(dishes.findById(d.getId()).orElseThrow().isActive()).isTrue();assertThat(orders.findById(order.id()).orElseThrow().getStatus()).isEqualTo(status);
        }
    }
    @Test void favoritesBlockDeletionWithoutRemovingCustomerPreferences() {
        var d=dish();var u=customer();member.setFavorite(actor(u),d.getId(),true);
        var result=removal.deleteDishes(admin(),List.of(d.getId()));
        assertThat(result.removedCount()).isZero();assertThat(result.items().getFirst().reason()).contains("Favorites");
        assertThat(favorites.findByUserIdAndDishId(u.getId(),d.getId())).isPresent();
        assertThat(dishes.findById(d.getId()).orElseThrow().isActive()).isTrue();
    }
    @Test void mixedBulkCommitsEligibleItemsAndLeavesUnselectedPageAndReferences() {
        var used=dish();place(guest(branch()),used);var saved=dish();var u=customer();member.setFavorite(actor(u),saved.getId(),true);
        var free=dish();var another=dish();var unselected=dish();
        var result=removal.deleteDishes(admin(),List.of(used.getId(),saved.getId(),free.getId(),free.getId(),another.getId(),Long.MAX_VALUE));
        assertThat(result.items()).hasSize(5);assertThat(result.removedCount()).isEqualTo(2);
        for(var d:List.of(used,saved,unselected))assertThat(dishes.existsById(d.getId())).isTrue();
        for(var d:List.of(free,another)) {assertThat(dishes.existsById(d.getId())).isFalse();assertThat(deletes("DELETE_DISH",d.getId())).isEqualTo(1);}
        assertThat(favorites.findByUserIdAndDishId(u.getId(),saved.getId())).isPresent();
    }
    @Test void invalidBulkRejectsEntireInputBeforeAnyMutation() {
        var d=dish();
        for(List<Long> ids:Arrays.asList(null,List.<Long>of(),Collections.nCopies(101,d.getId()),Arrays.asList(d.getId(),null),List.of(d.getId(),-1L))) {
            assertThatThrownBy(()->removal.deleteDishes(admin(),ids)).isInstanceOf(BusinessException.class);
            assertThat(dishes.existsById(d.getId())).isTrue();
        }
    }
    @Test void concurrentDuplicateDeletionHasExactlyOneSuccessAndOneAudit() throws Exception {
        var d=dish();var a=admin();var outcomes=concurrent(()->removal.deleteDishes(a,List.of(d.getId())).removedCount()==1,()->removal.deleteDishes(a,List.of(d.getId())).removedCount()==1);
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);assertThat(deletes("DELETE_DISH",d.getId())).isEqualTo(1);
    }
    @Test void concurrentOrderAndDeleteCannotLoseAnAcceptedOrder() throws Exception {
        var d=dish();var g=guest(branch());var a=admin();
        var outcomes=concurrent(()->{try{place(g,d);return true;}catch(BusinessException e){return false;}},
            ()->removal.deleteDishes(a,List.of(d.getId())).removedCount()==1);
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);
        if(outcomes.getFirst()) {assertThat(dishes.existsById(d.getId())).isTrue();assertThat(orders.findBySessionIdOrderByCreatedAt(g.sessionId())).hasSize(1);}
        else {assertThat(dishes.existsById(d.getId())).isFalse();assertThat(orders.findBySessionIdOrderByCreatedAt(g.sessionId())).isEmpty();}
    }
    @Test void concurrentFavoriteAndDeleteCannotLoseAnAcceptedFavorite() throws Exception {
        var d=dish();var u=customer();var a=admin();
        var outcomes=concurrent(()->{try{member.setFavorite(actor(u),d.getId(),true);return true;}catch(BusinessException e){return false;}},
            ()->removal.deleteDishes(a,List.of(d.getId())).removedCount()==1);
        assertThat(outcomes).containsExactlyInAnyOrder(true,false);
        assertThat(favorites.findByUserIdAndDishId(u.getId(),d.getId()).isPresent()).isEqualTo(outcomes.getFirst());
        assertThat(dishes.existsById(d.getId())).isEqualTo(outcomes.getFirst());
    }
    @Test void paidInvoiceSnapshotAndRevenueSurviveBlockedRemovalAndStateChange() {
        var b=branch();var d=dish();var g=guest(b);var order=place(g,d);
        for(var next:List.of(OrderStatus.CONFIRMED,OrderStatus.COOKING,OrderStatus.READY,OrderStatus.SERVED))ops.changeStatus(admin(),order.id(),next);
        var paid=ops.checkout(admin(),g.sessionId(),10,PaymentMethod.CASH);String name=d.getName();
        admin.dish(admin(),new AdminService.DishForm(d.getId(),"Changed-"+unique(),BigDecimal.valueOf(99999),"Đổi mô tả",d.getCategory().getId(),false));
        assertThat(removal.deleteDishes(admin(),List.of(d.getId())).removedCount()).isZero();
        assertThatThrownBy(()->removal.deleteBranch(admin(),b.getId())).isInstanceOf(ManagementException.class);admin.branchState(admin(),b.getId(),false);
        assertThat(ops.sessionOrders(admin(),g.sessionId()).getFirst().items().getFirst().name()).isEqualTo(name);
        assertThat(ops.sessionOrders(admin(),g.sessionId()).getFirst().total()).isEqualByComparingTo("20000");
        assertThat(invoices.findById(paid.getId()).orElseThrow().getTotal()).isEqualByComparingTo("18000");
        var today=LocalDate.now(Reports.ZONE);assertThat(reports.summary(admin(),b.getId(),today,today).total()).isEqualByComparingTo("18000");
    }
    @Test void disabledAndDeletedDishCannotBeOrderedFromStaleCart() {
        var d=dish();var g=guest(branch());admin.dishStates(admin(),List.of(d.getId()),false);
        assertThatThrownBy(()->place(g,d)).isInstanceOf(BusinessException.class);admin.dishStates(admin(),List.of(d.getId()),true);
        assertThat(removal.deleteDishes(admin(),List.of(d.getId())).removedCount()).isEqualTo(1);
        assertThatThrownBy(()->place(g,d)).isInstanceOf(BusinessException.class);assertThat(orders.findBySessionIdOrderByCreatedAt(g.sessionId())).isEmpty();
    }
    @Test void deleteEndpointsRequireCsrfAndGlobalAdminForEveryRole() throws Exception {
        var b=branch();var d=dish();var u=customer();var g=guest(branch());
        var guestSession=new DiningSession();guestSession.setId(g.sessionId());var guestCookie=new Cookie("UT_GUEST",jwt.guest(guestSession));
        var cookies=List.of(cookie(actor("manager")),cookie(actor("waiter")),cookie(actor("chef")),cookie(actor(u)));
        var paths=List.of("/admin/branches/"+b.getId()+"/delete","/admin/branches/"+b.getId()+"/state","/admin/dishes/"+d.getId()+"/delete","/admin/dishes/delete");
        for(String path:paths) {
            mvc.perform(post(path).cookie(cookie(admin())).param("ids",d.getId().toString()).param("active","false")).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(guestCookie).with(csrf()).param("ids",d.getId().toString()).param("active","false")).andExpect(redirectedUrl("/login?expired"));
            for(Cookie denied:cookies)mvc.perform(post(path).cookie(denied).with(csrf()).param("ids",d.getId().toString()).param("active","false")).andExpect(status().isForbidden());
        }
        for(Actor denied:List.of(actor("manager"),actor("waiter"),actor("chef"),actor(u),g)) {
            assertThatThrownBy(()->removal.deleteBranch(denied,b.getId())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->removal.deleteDishes(denied,List.of(d.getId()))).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(branches.existsById(b.getId())).isTrue();assertThat(dishes.existsById(d.getId())).isTrue();
    }
    @Test void mvcDeleteResultsMissingIdsNoGetDeletionAndConflictAreSafe() throws Exception {
        var b=branch();var d=dish();
        mvc.perform(get("/admin/branches/"+b.getId()+"/delete").cookie(cookie(admin()))).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/admin/dishes/"+d.getId()+"/delete").cookie(cookie(admin()))).andExpect(status().isMethodNotAllowed());
        for(String path:List.of("/admin/menu","/admin/branches"))mvc.perform(get(path).param("edit",Long.toString(Long.MAX_VALUE)).cookie(cookie(admin()))).andExpect(status().isNotFound());
        mvc.perform(post("/admin/branches").cookie(cookie(admin())).with(csrf()).param("name",b.getName()).param("address","Other").param("phone","0900000000").param("active","true")).andExpect(status().isConflict());
        mvc.perform(post("/admin/dishes/delete").cookie(cookie(admin())).with(csrf())).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/admin/dishes/"+d.getId()+"/delete").cookie(cookie(admin())).with(csrf())).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success","removalResults"));
        mvc.perform(post("/admin/branches/"+b.getId()+"/state").cookie(cookie(admin())).with(csrf()).param("active","false")).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        mvc.perform(post("/admin/branches/"+b.getId()+"/delete").cookie(cookie(admin())).with(csrf())).andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
    }
}
