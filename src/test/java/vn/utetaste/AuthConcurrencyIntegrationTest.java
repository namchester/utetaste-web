package vn.utetaste;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.utetaste.domain.AppUser;
import vn.utetaste.domain.Role;
import vn.utetaste.repository.AppUserRepository;
import vn.utetaste.repository.BranchRepository;
import vn.utetaste.security.Actor;
import vn.utetaste.security.JwtService;
import vn.utetaste.service.AdminService;
import vn.utetaste.web.AuthController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AuthConcurrencyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AppUserRepository users;
    @Autowired BranchRepository branches;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired AdminService admin;
    @Autowired AuthController auth;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void logoutRefreshesStaleAccountBeforeRevokingAndPreservesAdminChanges() throws Exception {
        var user = createUser();
        var oldToken = new Cookie("UT_ACCESS", jwt.staff(user));
        var loaded = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var logout = executor.submit(() -> transaction().executeWithoutResult(tx -> {
                // Simulate an in-flight request that read the account before an admin edit.
                users.findById(user.getId()).orElseThrow();
                loaded.countDown();
                await(resume);
                try {
                    mvc.perform(post("/logout").with(csrf()).cookie(oldToken))
                            .andExpect(status().is3xxRedirection())
                            .andExpect(redirectedUrl("/login?logout"));
                } catch (Exception e) {
                    throw new IllegalStateException("Logout request failed", e);
                }
            }));
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            String replacementPassword = "ChangedPassword@2026";
            admin.user(adminActor(), new AdminService.UserForm(user.getId(), user.getUsername(),
                    "Updated staff", Role.CHEF, user.getBranch().getId(), replacementPassword, false));
            resume.countDown();
            logout.get(15, TimeUnit.SECONDS);

            var updated = users.findById(user.getId()).orElseThrow();
            assertSoftly(softly -> {
                softly.assertThat(updated.getRole()).isEqualTo(Role.CHEF);
                softly.assertThat(updated.isActive()).isFalse();
                softly.assertThat(updated.getFullName()).isEqualTo("Updated staff");
                softly.assertThat(encoder.matches(replacementPassword, updated.getPasswordHash())).isTrue();
                softly.assertThat(updated.getTokenVersion()).isEqualTo(user.getTokenVersion() + 2);
            });
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousAdmittedLogoutsEachIncrementTokenVersion() throws Exception {
        var user = createUser();
        var actor = new Actor(user.getId(), user.getUsername(), user.getFullName(), user.getRole(),
                user.getBranch().getId(), null);
        int count = 4;
        var loaded = new CountDownLatch(count);
        var resume = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(count);
        try {
            var requests = new ArrayList<Future<?>>();
            for (int i = 0; i < count; i++) {
                requests.add(executor.submit(() -> transaction().executeWithoutResult(tx -> {
                    users.findById(user.getId()).orElseThrow();
                    loaded.countDown();
                    await(resume);
                    // All requests have already been authenticated; exercise revocation transactions.
                    auth.logout(actor, new MockHttpServletResponse());
                })));
            }
            assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            resume.countDown();
            for (var request : requests) {
                request.get(15, TimeUnit.SECONDS);
            }
            assertThat(users.findById(user.getId()).orElseThrow().getTokenVersion())
                    .isEqualTo(user.getTokenVersion() + count);
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private AppUser createUser() {
        var user = new AppUser();
        user.setUsername("race-" + UUID.randomUUID());
        user.setFullName("Concurrency test staff");
        user.setRole(Role.WAITER);
        user.setBranch(branches.findByActiveTrueOrderByName().get(0));
        user.setPasswordHash(encoder.encode("OriginalPassword@2026"));
        return users.saveAndFlush(user);
    }

    private Actor adminActor() {
        var user = users.findByUsername("admin").orElseThrow();
        return new Actor(user.getId(), user.getUsername(), user.getFullName(), Role.ADMIN, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating account updates", e);
        }
    }
}
