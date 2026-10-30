package vn.utetaste;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:member;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
class MemberAccountIntegrationTest {
    @Autowired MockMvc mvc;@Autowired MemberService members;@Autowired OrderService ops;@Autowired JwtService jwt;
    @Autowired AppUserRepository users;@Autowired RestaurantTableRepository tables;@Autowired BranchRepository branches;
    @Autowired DishRepository dishes;@Autowired FoodOrderRepository orders;@Autowired BranchDishRepository availability;
    @Autowired MemberFavoriteRepository favorites;@Autowired MemberPointEntryRepository points;@Autowired PasswordEncoder encoder;
    AppUser first,second;Actor a,b,waiter,chef,admin,guest;DiningSession session;Dish dish;
    static final String PASSWORD="Member@Test2026";
    Actor actor(AppUser u){return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    AppUser createUser(){String suffix=UUID.randomUUID().toString();var u=new AppUser();u.setUsername("m-"+suffix);u.setFullName("Khách "+suffix.substring(0,8));u.setEmail("m-"+suffix+"@example.test");u.setEmailVerified(true);u.setRole(Role.USER);u.setPasswordHash(encoder.encode(PASSWORD));return users.saveAndFlush(u);}
    @BeforeEach void prepare(){
        first=createUser();second=createUser();a=actor(first);b=actor(second);waiter=actor(users.findByUsername("waiter").orElseThrow());chef=actor(users.findByUsername("chef").orElseThrow());admin=actor(users.findByUsername("admin").orElseThrow());
        var t=new RestaurantTable();t.setCapacity(4);t.setBranch(branches.findById(waiter.branchId()).orElseThrow());t.setLabel("M-"+UUID.randomUUID().toString().substring(0,12));tables.saveAndFlush(t);session=ops.openTable(waiter,t.getId());guest=new Actor(null,"guest:"+session.getId(),t.getLabel(),Role.GUEST,waiter.branchId(),session.getId());dish=dishes.findAllByOrderByNameAsc().getFirst();
    }
    MemberIdentity identity(AppUser u){return new MemberIdentity(u.getId(),u.getFullName(),u.getTokenVersion());}
    Cookie access(AppUser u){return new Cookie("UT_ACCESS",jwt.staff(u));}Cookie qr(){return new Cookie("UT_GUEST",jwt.guest(session));}
    OrderService.PlaceRequest request(String key){return new OrderService.PlaceRequest(key,List.of(new OrderService.Line(dish.getId(),1)),"Ghi chú riêng");}
    OrderService.OrderView place(AppUser u){return ops.place(guest,request(UUID.randomUUID().toString()),u==null?null:identity(u));}
    String json(String key){return "{\"requestKey\":\""+key+"\",\"items\":[{\"dishId\":"+dish.getId()+",\"quantity\":1}],\"customerId\":"+second.getId()+",\"userId\":"+second.getId()+",\"role\":\"ADMIN\"}";}
    void served(long id){ops.changeStatus(waiter,id,OrderStatus.CONFIRMED);ops.changeStatus(chef,id,OrderStatus.COOKING);ops.changeStatus(chef,id,OrderStatus.READY);ops.changeStatus(waiter,id,OrderStatus.SERVED);}
    MemberCookieContext.QrContext table(){return new MemberCookieContext.QrContext(session.getId(),waiter.branchId(),"Chi nhánh",session.getTable().getLabel());}
    @Test void verifiedUserLogsInToCustomerDashboard() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username",first.getEmail()).param("password",PASSWORD)).andExpect(redirectedUrl("/account"));
        String html=mvc.perform(get("/account").cookie(access(first))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(first.getFullName(),first.getEmail(),"member-nav","Chưa bật cộng điểm tự động").doesNotContain("staff-navigation",first.getPasswordHash(),"tokenVersion");
    }
    @Test void profileOnlyUpdatesOwnAllowedFields() throws Exception {
        mvc.perform(post("/account/profile").with(csrf()).cookie(access(first)).param("fullName","  Tên mới  ").param("phone","+84 (901) 234-567").param("id",second.getId().toString()).param("userId",second.getId().toString()).param("role","ADMIN").param("email",second.getEmail()).param("tokenVersion","99").param("active","false"))
            .andExpect(redirectedUrl("/account/profile"));
        var saved=users.findById(first.getId()).orElseThrow();assertThat(saved.getFullName()).isEqualTo("Tên mới");assertThat(saved.getPhone()).isEqualTo("+84901234567");assertThat(saved.getRole()).isEqualTo(Role.USER);assertThat(saved.getEmail()).isEqualTo(first.getEmail());assertThat(saved.isActive()).isTrue();assertThat(saved.getTokenVersion()).isZero();assertThat(saved.getPasswordHash()).isEqualTo(first.getPasswordHash());assertThat(users.findById(second.getId()).orElseThrow().getFullName()).isEqualTo(second.getFullName());
    }
    @Test void invalidProfileIsRejectedAndOptionalPhoneCanBeCleared() throws Exception {
        for(var form:List.of(new MemberService.ProfileForm(" ",""),new MemberService.ProfileForm("Name","123"),new MemberService.ProfileForm("Name","abc0901234567"),new MemberService.ProfileForm("N".repeat(101),"")))assertThatThrownBy(()->members.updateProfile(a,form)).isInstanceOf(BusinessException.class);
        mvc.perform(post("/account/profile").with(csrf()).cookie(access(first)).param("fullName","").param("phone","0901234567")).andExpect(redirectedUrl("/account/profile")).andExpect(flash().attributeExists("message"));
        members.updateProfile(a,new MemberService.ProfileForm("Tên hợp lệ",""));assertThat(users.findById(first.getId()).orElseThrow().getPhone()).isNull();
    }
    @Test void profileAndFavoriteMutationsRequireCsrf() throws Exception {
        mvc.perform(post("/account/profile").cookie(access(first)).param("fullName","Attacker")).andExpect(status().isForbidden());
        mvc.perform(post("/api/member/favorites/"+dish.getId()).cookie(access(first)).contentType("application/json").content("{\"selected\":true}")).andExpect(status().isForbidden());
        mvc.perform(post("/account/favorites/"+dish.getId()).cookie(access(first)).param("selected","false")).andExpect(status().isForbidden());
    }
    @Test void dualCookiesBindOnlyNewOrderToVerifiedUserIgnoringForgedBody() throws Exception {
        var legacy=place(null);String key=UUID.randomUUID().toString();
        mvc.perform(post("/api/guest/orders").cookie(qr(),access(first)).with(csrf()).contentType("application/json").content(json(key))).andExpect(status().isOk());
        assertThat(orders.findBySessionIdAndRequestKey(session.getId(),key).orElseThrow().getCustomer().getId()).isEqualTo(first.getId());assertThat(orders.findById(legacy.id()).orElseThrow().getCustomer()).isNull();
        assertThat(members.history(a,null,null,null,"newest",0,10)).hasSize(1);assertThat(members.history(b,null,null,null,"newest",0,10)).isEmpty();
        mvc.perform(get("/account").cookie(qr(),access(first))).andExpect(status().isOk());mvc.perform(get("/guest/menu").cookie(qr(),access(first))).andExpect(status().isOk()).andExpect(content().string(containsString("data-favorite")));
    }
    @Test void guestWithoutMemberStillOrdersAndNoQrCannotCreateMemberOrder() throws Exception {
        String key=UUID.randomUUID().toString();mvc.perform(post("/api/guest/orders").cookie(qr()).with(csrf()).contentType("application/json").content(json(key))).andExpect(status().isOk());assertThat(orders.findBySessionIdAndRequestKey(session.getId(),key).orElseThrow().getCustomer()).isNull();
        mvc.perform(post("/api/guest/orders").cookie(access(first)).with(csrf()).contentType("application/json").content(json(UUID.randomUUID().toString()))).andExpect(status().isUnauthorized());
    }
    @Test void differentOwnersCannotClaimOrReplayExistingRequestKey() {
        String key=UUID.randomUUID().toString();var original=ops.place(guest,request(key),identity(first));
        assertThat(ops.place(guest,request(key),identity(first)).id()).isEqualTo(original.id());
        assertThatThrownBy(()->ops.place(guest,request(key),identity(second))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ops.place(guest,request(key),null)).isInstanceOf(BusinessException.class);
        String anon=UUID.randomUUID().toString();ops.place(guest,request(anon));assertThatThrownBy(()->ops.place(guest,request(anon),identity(first))).isInstanceOf(BusinessException.class);
    }
    @Test void concurrentDuplicateOwnedOrdersStayUnique() throws Exception {
        String key=UUID.randomUUID().toString();try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(()->ops.place(guest,request(key),identity(first)));var y=pool.submit(()->ops.place(guest,request(key),identity(first)));assertThat(x.get(20,TimeUnit.SECONDS).id()).isEqualTo(y.get(20,TimeUnit.SECONDS).id());}
        assertThat(members.qrOrders(identity(first),guest)).hasSize(1);
    }
    @Test void revokedOrStaffOrUnverifiedIdentityCannotOwnOrder() {
        first.setTokenVersion(1);users.saveAndFlush(first);
        assertThatThrownBy(()->ops.place(guest,request(UUID.randomUUID().toString()),new MemberIdentity(first.getId(),"Old",0))).isInstanceOf(AccessDeniedException.class);
        var staff=users.findByUsername("waiter").orElseThrow();assertThatThrownBy(()->ops.place(guest,request(UUID.randomUUID().toString()),identity(staff))).isInstanceOf(AccessDeniedException.class);
        second.setEmailVerified(false);users.saveAndFlush(second);assertThatThrownBy(()->place(second)).isInstanceOf(AccessDeniedException.class);assertThat(ops.guestOrders(guest)).isEmpty();
    }
    @Test void tamperedAndStaffAccessCookiesNeverAttachOwnership() throws Exception {
        for(Cookie cookie:List.of(new Cookie("UT_ACCESS","tampered.jwt.signature"),access(users.findByUsername("admin").orElseThrow()))){String key=UUID.randomUUID().toString();mvc.perform(post("/api/guest/orders").cookie(qr(),cookie).with(csrf()).contentType("application/json").content(json(key))).andExpect(status().isOk());assertThat(orders.findBySessionIdAndRequestKey(session.getId(),key).orElseThrow().getCustomer()).isNull();}
    }
    @Test void historyAndReceiptNeverExposeOtherOwnersOrSharedSpending() throws Exception {
        var mine=place(first);var other=place(second);var anon=place(null);for(var o:List.of(mine,other,anon))served(o.id());var invoice=ops.checkout(admin,session.getId(),10,PaymentMethod.CASH);
        for(String path:List.of("/account/orders/"+mine.id(),"/account/orders/"+mine.id()+"/receipt")){
            String html=mvc.perform(get(path).cookie(access(first))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(html).contains(mine.code(),invoice.getCode(),"Tổng món của đơn này").doesNotContain(other.code(),anon.code(),second.getEmail(),"Tổng thực thu",">"+invoice.getTotal()+"<");
        }
        mvc.perform(get("/account/orders/"+other.id()).cookie(access(first))).andExpect(status().isForbidden());mvc.perform(get("/account/orders/"+other.id()+"/receipt").cookie(access(first))).andExpect(status().isForbidden());
        var rows=members.history(a,null,null,null,"newest",0,10);assertThat(rows).hasSize(1);assertThat(rows.getContent().getFirst().order().total()).isEqualByComparingTo(mine.total());assertThat(members.overview(a,null).paidOrders()).isEqualTo(1);
        mvc.perform(get("/api/guest/orders").cookie(qr(),access(first))).andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(1)).andExpect(jsonPath("$.orders[0].id").value(mine.id()));
        String qrHtml=mvc.perform(get("/guest/orders").cookie(qr(),access(first))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(qrHtml).doesNotContain(other.code(),anon.code(),"Tổng thực thu");
        // Removing or expiring UT_ACCESS must never expose another member's orders via anonymous QR.
        mvc.perform(get("/api/guest/orders").cookie(qr())).andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(1)).andExpect(jsonPath("$.orders[0].id").value(anon.id()));
        String anonymous=mvc.perform(get("/guest/orders").cookie(qr())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(anonymous).doesNotContain(mine.code(),other.code(),"Tổng thực thu",invoice.getCode());
    }
    @Test void historyFilteringSortingPaginationAndSnapshotPrices() throws Exception {
        var oldest=place(first);ops.changeStatus(waiter,oldest.id(),OrderStatus.CONFIRMED);var newest=place(first);place(second);
        assertThat(members.history(a,null,null,null,"oldest",0,10).getContent().getFirst().order().id()).isEqualTo(oldest.id());assertThat(members.history(a,null,null,null,"newest",0,10).getContent().getFirst().order().id()).isEqualTo(newest.id());
        assertThat(members.history(a,null,null,OrderStatus.NEW,"newest",0,10)).hasSize(1);assertThat(members.history(a,LocalDate.now(Reports.ZONE).minusDays(1),LocalDate.now(Reports.ZONE).plusDays(1),null,"newest",0,10)).hasSize(2);assertThat(members.history(a,LocalDate.of(2000,1,1),LocalDate.of(2000,1,2),null,"newest",0,10)).isEmpty();
        for(int i=0;i<10;i++)place(first);var page=members.history(a,null,null,null,"newest",1,10);assertThat(page.getTotalElements()).isEqualTo(12);assertThat(page).hasSize(2);
        var price=dish.getPrice();try{dish.setPrice(price.add(BigDecimal.valueOf(1234)));dishes.saveAndFlush(dish);assertThat(members.detail(a,oldest.id()).order().items().getFirst().price()).isEqualByComparingTo(price);}finally{dish.setPrice(price);dishes.saveAndFlush(dish);}
        mvc.perform(get("/account/orders?size=10&page=1&sort=oldest").cookie(access(first))).andExpect(status().isOk()).andExpect(content().string(containsString("Trang 2 / 2")));
    }
    @Test void malformedHistoryInputsAreRejected() throws Exception {
        for(String query:List.of("from=2026-10-30&to=2026-10-01","from=2000-01-01&to=2026-10-30","status=INVALID","page=-1","size=100000","sort=sql","from=invalid"))mvc.perform(get("/account/orders?"+query).cookie(access(first))).andExpect(status().isBadRequest());
    }
    @Test void favoritesAreIdempotentAndScopedToPrincipal() throws Exception {
        String url="/api/member/favorites/"+dish.getId();for(int i=0;i<2;i++)mvc.perform(post(url).cookie(access(first)).with(csrf()).contentType("application/json").content("{\"selected\":true,\"userId\":"+second.getId()+"}")).andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(true));
        assertThat(favorites.countByUserId(first.getId())).isEqualTo(1);assertThat(favorites.countByUserId(second.getId())).isZero();
        mvc.perform(post(url).cookie(access(second)).with(csrf()).contentType("application/json").content("{\"selected\":false,\"userId\":"+first.getId()+"}")).andExpect(status().isOk());assertThat(favorites.countByUserId(first.getId())).isEqualTo(1);
        members.setFavorite(a,dish.getId(),false);members.setFavorite(a,dish.getId(),false);assertThat(favorites.countByUserId(first.getId())).isZero();
    }
    @Test void concurrentFavoriteAddsCreateOnlyOneRecord() throws Exception {
        try(var pool=Executors.newFixedThreadPool(4)){var jobs=new ArrayList<Future<?>>();for(int i=0;i<4;i++)jobs.add(pool.submit(()->members.setFavorite(a,dish.getId(),true)));for(var job:jobs)job.get(20,TimeUnit.SECONDS);}
        assertThat(favorites.countByUserId(first.getId())).isEqualTo(1);
    }
    @Test void favoriteSelectionNeedsValidQrAndAvailableBranchDish() throws Exception {
        members.setFavorite(a,dish.getId(),true);assertThatThrownBy(()->members.choose(a,dish.getId(),null)).isInstanceOf(BusinessException.class);
        mvc.perform(get("/account/favorites/"+dish.getId()+"/choose").cookie(access(first))).andExpect(redirectedUrl("/account/favorites")).andExpect(flash().attributeExists("message"));
        mvc.perform(get("/account/favorites/"+dish.getId()+"/choose").cookie(access(first),qr())).andExpect(redirectedUrl("/guest/menu?pick="+dish.getId()+"#cart-panel"));assertThat(ops.guestOrders(guest)).isEmpty();
        var bd=availability.findByBranchIdAndDishId(waiter.branchId(),dish.getId()).orElseThrow();try{bd.setAvailable(false);availability.saveAndFlush(bd);assertThatThrownBy(()->members.choose(a,dish.getId(),table())).isInstanceOf(BusinessException.class);assertThat(members.favorites(a,table(),0,10).getContent().getFirst().atTable()).isFalse();}finally{bd.setAvailable(true);availability.saveAndFlush(bd);}
        ops.closeEmpty(waiter,session.getId());mvc.perform(get("/account/favorites/"+dish.getId()+"/choose").cookie(access(first),qr())).andExpect(redirectedUrl("/account/favorites"));
    }
    @Test void inactiveDishStaysVisibleInFavoritesButCannotBeChosenOrNewlySaved() {
        members.setFavorite(a,dish.getId(),true);try{dish.setActive(false);dishes.saveAndFlush(dish);assertThat(members.favorites(a,table(),0,10).getContent().getFirst().active()).isFalse();assertThatThrownBy(()->members.choose(a,dish.getId(),table())).isInstanceOf(BusinessException.class);assertThatThrownBy(()->members.setFavorite(b,dish.getId(),true)).isInstanceOf(BusinessException.class);}finally{dish.setActive(true);dishes.saveAndFlush(dish);}
    }
    @Test void realLedgerBalanceIsPrivateAndCannotBeChangedOverHttp() throws Exception {
        var credit=new MemberPointEntry();credit.setUser(first);credit.setReferenceKey("test-credit-"+UUID.randomUUID());credit.setPoints(8);credit.setReason("Điều chỉnh kiểm thử");points.saveAndFlush(credit);
        var debit=new MemberPointEntry();debit.setUser(first);debit.setReferenceKey("test-debit-"+UUID.randomUUID());debit.setPoints(-3);debit.setReason("Hoàn điều chỉnh kiểm thử");points.saveAndFlush(debit);
        assertThat(members.balance(a)).isEqualTo(5);assertThat(members.balance(b)).isZero();assertThat(members.points(a,0,10)).hasSize(2);assertThat(members.points(b,0,10)).isEmpty();
        mvc.perform(post("/account/points").with(csrf()).cookie(access(first)).param("points","10000")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/member/points").with(csrf()).cookie(access(first)).contentType("application/json").content("{\"points\":1000}")).andExpect(status().isNotFound());assertThat(members.balance(a)).isEqualTo(5);
    }
    @Test void ledgerRejectsDuplicateReferencesAndZeroPoints() {
        String reference="unique-"+UUID.randomUUID();var p=new MemberPointEntry();p.setUser(first);p.setPoints(1);p.setReason("Test");p.setReferenceKey(reference);points.saveAndFlush(p);
        var duplicate=new MemberPointEntry();duplicate.setUser(second);duplicate.setPoints(1);duplicate.setReason("Test");duplicate.setReferenceKey(reference);assertThatThrownBy(()->points.saveAndFlush(duplicate)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var zero=new MemberPointEntry();zero.setUser(first);zero.setReferenceKey("zero-"+UUID.randomUUID());zero.setReason("Test");zero.setPoints(0);assertThatThrownBy(()->points.saveAndFlush(zero)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void paymentWorkflowIsUnchangedAndNeverAwardsUnverifiedPayer() {
        var mine=place(first);var other=place(second);served(mine.id());served(other.id());assertThat(members.balance(a)).isZero();var invoice=ops.checkout(admin,session.getId(),10,PaymentMethod.CASH);assertThat(ops.checkout(admin,session.getId(),10,PaymentMethod.CASH).getId()).isEqualTo(invoice.getId());assertThat(members.balance(a)).isZero();assertThat(members.balance(b)).isZero();assertThat(members.overview(a,null).paidOrders()).isEqualTo(1);
    }
    @Test void allMemberScreensRenderPopulatedAndEmptyStatesWithoutStaffControls() throws Exception {
        var mine=place(first);members.setFavorite(a,dish.getId(),true);
        for(String path:List.of("/account","/account/profile","/account/security","/account/orders","/account/orders/"+mine.id(),"/account/favorites","/account/favorites/"+dish.getId(),"/account/points","/account/password")){
            String html=mvc.perform(get(path).cookie(access(first),qr())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(html).contains("member-nav").doesNotContain("staff-navigation","data-status=\"CONFIRMED\"");
        }
        for(String path:List.of("/account","/account/orders","/account/favorites","/account/points"))mvc.perform(get(path).cookie(access(second))).andExpect(status().isOk());
    }
    @Test void roleIsolationAndRevokedJwtAreEnforced() throws Exception {
        for(String path:List.of("/staff","/admin/users","/staff/reports"))mvc.perform(get(path).cookie(access(first))).andExpect(status().isForbidden());
        for(String path:List.of("/account/profile","/account/orders","/account/favorites","/account/points","/account/security")){mvc.perform(get(path).cookie(access(users.findByUsername("admin").orElseThrow()))).andExpect(status().isForbidden());mvc.perform(get(path).cookie(qr())).andExpect(status().is3xxRedirection());}
        Cookie old=access(first);first.setTokenVersion(1);users.saveAndFlush(first);mvc.perform(get("/account/orders").cookie(old)).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/staff/orders/123/status").with(csrf()).cookie(access(second)).contentType("application/json").content("{\"status\":\"CONFIRMED\"}")).andExpect(status().isForbidden());
    }
    @Test void missingFavoriteAndMalformedSelectionDoNotChangeData() throws Exception {
        mvc.perform(post("/api/member/favorites/"+dish.getId()).with(csrf()).cookie(access(first)).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/account/favorites/"+dish.getId()).cookie(access(second))).andExpect(status().isBadRequest());assertThat(favorites.countByUserId(first.getId())).isZero();
    }
    @Test void joiningAndLoggingOutPreserveIndependentCookieLifecycle() throws Exception {
        Cookie member=access(first);String joined=mvc.perform(post("/t/"+session.getTable().getQrKey()+"/join").with(csrf()).cookie(member)).andExpect(redirectedUrl("/guest/menu")).andReturn().getResponse().getHeader("Set-Cookie");assertThat(joined).startsWith("UT_GUEST=").doesNotContain("UT_ACCESS");
        var owned=place(first);String logout=mvc.perform(post("/logout").with(csrf()).cookie(member,qr())).andExpect(status().is3xxRedirection()).andReturn().getResponse().getHeader("Set-Cookie");assertThat(logout).startsWith("UT_ACCESS=").doesNotContain("UT_GUEST");
        mvc.perform(get("/account/orders").cookie(member,qr())).andExpect(status().is3xxRedirection());mvc.perform(get("/guest/menu").cookie(qr())).andExpect(status().isOk());mvc.perform(get("/api/guest/orders").cookie(member,qr())).andExpect(status().isOk()).andExpect(jsonPath("$.orders.length()").value(0));assertThat(orders.findById(owned.id()).orElseThrow().getCustomer().getId()).isEqualTo(first.getId());
    }
    @Test void favoriteQuickChooseCannotCrossBranchOrTrustForgedSessionContext() throws Exception {
        members.setFavorite(a,dish.getId(),true);var branch=new Branch();branch.setName("Member branch "+UUID.randomUUID());branch.setAddress("Test address");branch.setPhone("0901234567");branches.saveAndFlush(branch);
        var t=new RestaurantTable();t.setBranch(branch);t.setCapacity(4);t.setLabel("Other");tables.saveAndFlush(t);var other=ops.openTable(admin,t.getId());
        var valid=new MemberCookieContext.QrContext(other.getId(),branch.getId(),branch.getName(),t.getLabel());assertThatThrownBy(()->members.choose(a,dish.getId(),valid)).isInstanceOf(BusinessException.class);
        var forged=new MemberCookieContext.QrContext(other.getId(),waiter.branchId(),"Forged",t.getLabel());assertThatThrownBy(()->members.choose(a,dish.getId(),forged)).isInstanceOf(BusinessException.class);
        mvc.perform(get("/account/favorites/"+dish.getId()+"/choose").cookie(access(first),new Cookie("UT_GUEST",jwt.guest(other)))).andExpect(redirectedUrl("/account/favorites")).andExpect(flash().attributeExists("message"));
    }
    @Test void concurrentLedgerReferenceInsertionCannotDoubleCredit() throws Exception {
        String reference="concurrent-"+UUID.randomUUID();var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<Boolean> attempt=()->{ready.countDown();start.await(10,TimeUnit.SECONDS);var e=new MemberPointEntry();e.setUser(first);e.setReferenceKey(reference);e.setPoints(1);e.setReason("Test concurrency");try{points.saveAndFlush(e);return true;}catch(org.springframework.dao.DataIntegrityViolationException expected){return false;}};
        try(var pool=Executors.newFixedThreadPool(2)){var x=pool.submit(attempt);var y=pool.submit(attempt);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();assertThat(List.of(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        assertThat(members.balance(a)).isEqualTo(1);assertThat(members.balance(b)).isZero();
    }
    @Test void memberPasswordChangeRevokesOldJwtWithoutRemovingQrOrHistory() throws Exception {
        var own=place(first);Cookie old=access(first);String changed="ChangedMember@Test2026";
        mvc.perform(post("/account/password").cookie(old,qr()).param("oldPassword",PASSWORD).param("newPassword",changed)).andExpect(status().isForbidden());
        mvc.perform(post("/account/password").cookie(old,qr()).with(csrf()).param("oldPassword","Incorrect@Test2026").param("newPassword",changed)).andExpect(status().isBadRequest());
        mvc.perform(post("/account/password").cookie(old,qr()).with(csrf()).param("oldPassword",PASSWORD).param("newPassword",changed)).andExpect(redirectedUrl("/login?changed"));
        var updated=users.findById(first.getId()).orElseThrow();assertThat(encoder.matches(changed,updated.getPasswordHash())).isTrue();assertThat(encoder.matches(PASSWORD,updated.getPasswordHash())).isFalse();assertThat(updated.getTokenVersion()).isEqualTo(1);
        mvc.perform(get("/account/orders").cookie(old)).andExpect(status().is3xxRedirection());mvc.perform(get("/guest/menu").cookie(qr())).andExpect(status().isOk());
        mvc.perform(post("/login").with(csrf()).param("username",first.getEmail()).param("password",PASSWORD)).andExpect(redirectedUrl("/login?error"));mvc.perform(post("/login").with(csrf()).param("username",first.getEmail()).param("password",changed)).andExpect(redirectedUrl("/account"));
        mvc.perform(get("/account/orders/"+own.id()).cookie(access(updated))).andExpect(status().isOk());
    }
}
