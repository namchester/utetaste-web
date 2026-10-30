package vn.utetaste;

import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.AppUser;
import vn.utetaste.domain.Branch;
import vn.utetaste.domain.DiningSession;
import vn.utetaste.domain.RestaurantTable;
import vn.utetaste.domain.Role;
import vn.utetaste.repository.AppUserRepository;
import vn.utetaste.repository.BranchRepository;
import vn.utetaste.repository.DiningSessionRepository;
import vn.utetaste.repository.RestaurantTableRepository;
import vn.utetaste.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises authentication against an isolated, disposable database, never the running application. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:auth-security;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class AuthSecurityIntegrationTest {
    private static final String PASSWORD = "Utetaste@Test2026";

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired AppUserRepository users;
    @Autowired BranchRepository branches;
    @Autowired RestaurantTableRepository tables;
    @Autowired DiningSessionRepository sessions;

    private AppUser staff;
    private Branch branch;
    private Branch otherBranch;
    private DiningSession dining;

    @BeforeEach
    void prepare() {
        branch = users.findByUsername("waiter").orElseThrow().getBranch();
        otherBranch = users.findByUsername("waiter2").orElseThrow().getBranch();
        staff = createUser(Role.WAITER, branch);
        var table = new RestaurantTable();
        table.setBranch(branch);
        table.setLabel("Auth-" + UUID.randomUUID().toString().substring(0, 12));
        table.setCapacity(4);
        tables.save(table);
        dining = new DiningSession();
        dining.setTable(table);
        sessions.saveAndFlush(dining);
    }

    @Test
    void getLogoutDoesNotRevokeAuthentication() throws Exception {
        var cookie = staffCookie(staff);
        mvc.perform(get("/logout").cookie(cookie))
            .andExpect(status().isMethodNotAllowed());
        assertStaffTokenWorks(cookie);
        assertThat(users.findById(staff.getId()).orElseThrow().getTokenVersion()).isZero();
    }

    @Test
    void logoutWithoutCsrfDoesNotRevokeAuthentication() throws Exception {
        var cookie = staffCookie(staff);
        mvc.perform(post("/logout").cookie(cookie))
            .andExpect(status().isForbidden());
        assertStaffTokenWorks(cookie);
        assertThat(users.findById(staff.getId()).orElseThrow().getTokenVersion()).isZero();
    }

    @Test
    void renderedLogoutFormRevokesStaffTokenAndPreservesGuestCookie() throws Exception {
        var staffCookie = staffCookie(staff);
        var guestCookie = guestCookie();
        var page = mvc.perform(get("/account/password").cookie(staffCookie, guestCookie))
            .andExpect(status().isOk()).andReturn();
        var response = mvc.perform(post("/logout")
                .cookie(staffCookie, guestCookie, csrfCookie(page))
                .param("_csrf", formToken(page, "/logout")))
            .andExpect(redirectedUrl("/login?logout")).andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
            .anyMatch(value -> value.startsWith("UT_ACCESS=") && value.contains("Max-Age=0"))
            .noneMatch(value -> value.startsWith("UT_GUEST="));
        assertRevoked(staffCookie);
        mvc.perform(get("/api/guest/orders").cookie(guestCookie)).andExpect(status().isOk());
        assertThat(users.findById(staff.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
    }

    @Test
    void guestLeaveRequiresCsrf() throws Exception {
        mvc.perform(post("/guest/leave").cookie(guestCookie()))
            .andExpect(status().isForbidden());
    }

    @Test
    void loadingAuthenticatedResourcesDoesNotDeleteBrowserCsrfCookie() throws Exception {
        var access = staffCookie(staff);
        var page = mvc.perform(get("/account/password").cookie(access))
            .andExpect(status().isOk()).andReturn();
        var csrfCookie = csrfCookie(page);
        for (String resource : new String[]{"/css/app.css", "/js/app.js", "/images/mark.svg"}) {
            var response = mvc.perform(get(resource).cookie(access, csrfCookie))
                .andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getHeaders("Set-Cookie"))
                .noneMatch(value -> value.startsWith("XSRF-TOKEN=") && value.contains("Max-Age=0"));
        }
        mvc.perform(post("/logout").cookie(access, csrfCookie)
                .param("_csrf", formToken(page, "/logout")))
            .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void renderedGuestLeaveFormClearsOnlyGuestCookie() throws Exception {
        var staffCookie = staffCookie(staff);
        var guestCookie = guestCookie();
        var page = mvc.perform(get("/guest/orders").cookie(staffCookie, guestCookie))
            .andExpect(status().isOk()).andReturn();
        var response = mvc.perform(post("/guest/leave")
                .cookie(staffCookie, guestCookie, csrfCookie(page))
                .param("_csrf", formToken(page, "/guest/leave")))
            .andExpect(redirectedUrl("/")).andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
            .anyMatch(value -> value.startsWith("UT_GUEST=") && value.contains("Max-Age=0"))
            .noneMatch(value -> value.startsWith("UT_ACCESS="));
        assertStaffTokenWorks(staffCookie);
        assertThat(sessions.findById(dining.getId()).orElseThrow().isOpen()).isTrue();
    }

    @Test
    void loginUsesRenderedCsrfTokenAndIssuesHttpOnlyStaffCookie() throws Exception {
        var page = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        var response = mvc.perform(post("/login").cookie(csrfCookie(page), guestCookie())
                .param("_csrf", formToken(page, "/login"))
                .param("username", staff.getUsername()).param("password", PASSWORD))
            .andExpect(redirectedUrl("/staff")).andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
            .anyMatch(value -> value.startsWith("UT_ACCESS=")
                && value.contains("HttpOnly") && value.contains("SameSite=Lax"))
            .noneMatch(value -> value.startsWith("UT_GUEST="));
        assertStaffTokenWorks(accessCookie(response));
    }

    @Test
    void loginWithoutCsrfCannotIssueAccessCookie() throws Exception {
        var response = mvc.perform(post("/login")
                .param("username", staff.getUsername()).param("password", PASSWORD))
            .andExpect(status().isForbidden()).andReturn().getResponse();
        assertNoAccessCookie(response);
    }

    static Stream<Arguments> invalidLoginInputs() {
        return Stream.of(
            Arguments.of(null, PASSWORD),
            Arguments.of("unknown", null),
            Arguments.of("", PASSWORD),
            Arguments.of("   ", PASSWORD),
            Arguments.of("invalid username", PASSWORD),
            Arguments.of("u".repeat(100), PASSWORD),
            Arguments.of("unknown", ""),
            Arguments.of("unknown", "p".repeat(73)),
            Arguments.of("unknown", "đ".repeat(40))
        );
    }

    @ParameterizedTest(name = "invalid login input {index}")
    @MethodSource("invalidLoginInputs")
    void invalidLoginInputReturnsGenericFormError(String username, String password) throws Exception {
        var request = post("/login").with(csrf());
        if (username != null) request.param("username", username);
        if (password != null) request.param("password", password);
        var response = mvc.perform(request).andExpect(redirectedUrl("/login?error"))
            .andReturn().getResponse();
        assertNoAccessCookie(response);
    }

    @Test
    void wrongPasswordAndUnknownAccountHaveSamePublicOutcome() throws Exception {
        for (String username : new String[]{staff.getUsername(), "missing-" + UUID.randomUUID()}) {
            var response = mvc.perform(post("/login").with(csrf())
                    .param("username", username).param("password", "WrongPassword@2026"))
                .andExpect(redirectedUrl("/login?error")).andReturn().getResponse();
            assertNoAccessCookie(response);
        }
    }

    @Test
    void overlongPasswordForExistingAccountReturnsGenericFormError() throws Exception {
        for (String password : new String[]{"p".repeat(73), "đ".repeat(40)}) {
            var response = mvc.perform(post("/login").with(csrf())
                    .param("username", staff.getUsername()).param("password", password))
                .andExpect(redirectedUrl("/login?error")).andReturn().getResponse();
            assertNoAccessCookie(response);
        }
    }

    @Test
    void disabledAccountCannotLogInOrReuseIssuedToken() throws Exception {
        var oldCookie = staffCookie(staff);
        staff.setActive(false);
        users.saveAndFlush(staff);
        assertLoginRejected(staff);
        assertRevoked(oldCookie);
    }

    @Test
    void inactiveBranchCannotLogInOrReuseIssuedToken() throws Exception {
        var oldCookie = staffCookie(staff);
        branch.setActive(false);
        branches.saveAndFlush(branch);
        assertLoginRejected(staff);
        assertRevoked(oldCookie);
    }

    @Test
    void staffWithoutBranchIsRejectedGracefully() throws Exception {
        staff.setBranch(null);
        users.saveAndFlush(staff);
        assertLoginRejected(staff);
        assertRevoked(staffCookie(staff));
    }

    @Test
    void eightFailedLoginsBlockEvenCorrectCredentialsWithFormMessage() throws Exception {
        for (int i = 0; i < 8; i++) {
            mvc.perform(post("/login").with(csrf())
                    .param("username", staff.getUsername()).param("password", "WrongPassword@2026"))
                .andExpect(redirectedUrl("/login?error"));
        }
        var response = mvc.perform(post("/login").with(csrf())
                .param("username", staff.getUsername()).param("password", PASSWORD))
            .andExpect(redirectedUrl("/login?locked")).andReturn().getResponse();
        assertNoAccessCookie(response);
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesOldToken() throws Exception {
        var oldCookie = staffCookie(staff);
        mvc.perform(post("/account/password").with(csrf()).cookie(oldCookie)
                .param("oldPassword", "WrongPassword@2026").param("newPassword", "NewPassword@2026"))
            .andExpect(status().isBadRequest());
        assertStaffTokenWorks(oldCookie);
        mvc.perform(post("/account/password").with(csrf()).cookie(oldCookie)
                .param("oldPassword", PASSWORD).param("newPassword", "NewPassword@2026"))
            .andExpect(redirectedUrl("/login?changed"));
        assertRevoked(oldCookie);
        mvc.perform(post("/login").with(csrf()).param("username", staff.getUsername())
                .param("password", PASSWORD)).andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("username", staff.getUsername())
                .param("password", "NewPassword@2026")).andExpect(redirectedUrl("/staff"));
    }

    @Test
    void adminRoleChangeRevokesPreviousTokenAndUsesCurrentPermissions() throws Exception {
        var oldCookie = staffCookie(staff);
        updateStaff(Role.CHEF, branch, true);
        assertRevoked(oldCookie);
        var currentCookie = staffCookie(users.findById(staff.getId()).orElseThrow());
        mvc.perform(get("/staff/kitchen").cookie(currentCookie)).andExpect(status().isOk());
        mvc.perform(get("/staff/tables").cookie(currentCookie)).andExpect(status().isForbidden());
    }

    @Test
    void adminDisableRevokesPreviousTokenAndPreventsLogin() throws Exception {
        var oldCookie = staffCookie(staff);
        updateStaff(Role.WAITER, branch, false);
        assertRevoked(oldCookie);
        assertLoginRejected(users.findById(staff.getId()).orElseThrow());
    }

    @Test
    void adminBranchChangeRevokesPreviousTokenAndBlocksFormerBranch() throws Exception {
        var oldCookie = staffCookie(staff);
        updateStaff(Role.WAITER, otherBranch, true);
        assertRevoked(oldCookie);
        var currentCookie = staffCookie(users.findById(staff.getId()).orElseThrow());
        mvc.perform(get("/staff/sessions/" + dining.getId()).cookie(currentCookie))
            .andExpect(status().isForbidden());
    }

    static Stream<Arguments> protectedPages() {
        return Stream.of(
            Arguments.of(Role.ADMIN, "/admin/users", 200),
            Arguments.of(Role.MANAGER, "/admin/users", 403),
            Arguments.of(Role.WAITER, "/admin/users", 403),
            Arguments.of(Role.CHEF, "/admin/users", 403),
            Arguments.of(Role.MANAGER, "/staff/reports", 200),
            Arguments.of(Role.WAITER, "/staff/reports", 403),
            Arguments.of(Role.CHEF, "/staff/reports", 403)
        );
    }

    @ParameterizedTest
    @MethodSource("protectedPages")
    void pagePermissionsAreEnforcedOnServer(Role role, String path, int expectedStatus) throws Exception {
        var user = createUser(role, role == Role.ADMIN ? null : branch);
        mvc.perform(get(path).cookie(staffCookie(user)))
            .andExpect(status().is(expectedStatus));
    }

    @Test
    void managerCannotReadAnotherBranchSessionOrChangeItsTables() throws Exception {
        var manager = createUser(Role.MANAGER, otherBranch);
        var cookie = staffCookie(manager);
        mvc.perform(get("/staff/sessions/" + dining.getId()).cookie(cookie))
            .andExpect(status().isForbidden());
        mvc.perform(post("/staff/tables/" + dining.getTable().getId() + "/rotate")
                .with(csrf()).cookie(cookie))
            .andExpect(status().isForbidden());
    }

    @Test
    void staffAndGuestCookiesCannotSubstituteForEachOther() throws Exception {
        mvc.perform(get("/api/guest/orders").cookie(staffCookie(staff)))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/staff/orders/1/status").with(csrf()).cookie(guestCookie())
                .contentType("application/json").content("{\"status\":\"CONFIRMED\"}"))
            .andExpect(status().isUnauthorized());
    }

    private AppUser createUser(Role role, Branch assignedBranch) {
        var user = new AppUser();
        user.setUsername("auth_" + UUID.randomUUID().toString().replace("-", ""));
        user.setFullName("Nhân viên kiểm thử xác thực");
        user.setPasswordHash(users.findByUsername("waiter").orElseThrow().getPasswordHash());
        user.setRole(role);
        user.setBranch(assignedBranch);
        return users.saveAndFlush(user);
    }

    private Cookie staffCookie(AppUser user) {
        return new Cookie("UT_ACCESS", jwt.staff(user));
    }

    private Cookie guestCookie() {
        return new Cookie("UT_GUEST", jwt.guest(dining));
    }

    private void assertStaffTokenWorks(Cookie cookie) throws Exception {
        mvc.perform(get("/account/password").cookie(cookie)).andExpect(status().isOk());
    }

    private void assertRevoked(Cookie cookie) throws Exception {
        mvc.perform(get("/account/password").cookie(cookie))
            .andExpect(redirectedUrl("/login?expired"));
    }

    private void assertLoginRejected(AppUser user) throws Exception {
        var response = mvc.perform(post("/login").with(csrf())
                .param("username", user.getUsername()).param("password", PASSWORD))
            .andExpect(redirectedUrl("/login?error")).andReturn().getResponse();
        assertNoAccessCookie(response);
    }

    private void updateStaff(Role role, Branch assignedBranch, boolean active) throws Exception {
        var admin = users.findByUsername("admin").orElseThrow();
        mvc.perform(post("/admin/users").with(csrf()).cookie(staffCookie(admin))
                .param("id", staff.getId().toString()).param("username", staff.getUsername())
                .param("fullName", staff.getFullName()).param("role", role.name())
                .param("branchId", assignedBranch.getId().toString()).param("password", "")
                .param("active", Boolean.toString(active)))
            .andExpect(redirectedUrl("/admin/users"));
    }

    private static void assertNoAccessCookie(MockHttpServletResponse response) {
        assertThat(response.getHeaders("Set-Cookie"))
            .noneMatch(value -> value.startsWith("UT_ACCESS="));
    }

    private static Cookie accessCookie(MockHttpServletResponse response) {
        String value = response.getHeaders("Set-Cookie").stream()
            .filter(header -> header.startsWith("UT_ACCESS=")).findFirst().orElseThrow();
        return new Cookie("UT_ACCESS", value.substring("UT_ACCESS=".length(), value.indexOf(';')));
    }

    private static Cookie csrfCookie(MvcResult page) {
        Cookie cookie = page.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("Rendered page must issue the browser's CSRF cookie").isNotNull();
        return cookie;
    }

    // Unlike the MockMvc csrf() helper, this does not replace the application's
    // CookieCsrfTokenRepository with a session-based test repository.
    private static RequestPostProcessor csrf() {
        return request -> {
            var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
            var token = repository.generateToken(request);
            var cookies = new java.util.ArrayList<Cookie>();
            if (request.getCookies() != null) {
                for (var cookie : request.getCookies()) {
                    if (!cookie.getName().equals("XSRF-TOKEN")) cookies.add(cookie);
                }
            }
            cookies.add(new Cookie("XSRF-TOKEN", token.getToken()));
            request.setCookies(cookies.toArray(Cookie[]::new));
            new XorCsrfTokenRequestAttributeHandler().handle(request,
                new MockHttpServletResponse(), () -> token);
            var masked = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            request.setParameter(masked.getParameterName(), masked.getToken());
            return request;
        };
    }

    private static String formToken(MvcResult page, String action) throws Exception {
        var form = Pattern.compile("<form\\b[^>]*\\baction=\"" + Pattern.quote(action)
                + "\"[^>]*>(.*?)</form>", Pattern.DOTALL)
            .matcher(page.getResponse().getContentAsString());
        assertThat(form.find()).as("Rendered POST form %s", action).isTrue();
        var field = Pattern.compile("<input\\b[^>]*\\bname=\"_csrf\"[^>]*>")
            .matcher(form.group(1));
        assertThat(field.find()).as("CSRF hidden field in %s", action).isTrue();
        var value = Pattern.compile("\\bvalue=\"([^\"]+)\"").matcher(field.group());
        assertThat(value.find()).isTrue();
        return value.group(1);
    }
}
