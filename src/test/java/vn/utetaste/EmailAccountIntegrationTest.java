package vn.utetaste;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.utetaste.domain.*;
import vn.utetaste.domain.EmailOtp.Purpose;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties={"app.mail.enabled=true","app.mail.from=no-reply@example.test"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE) @ActiveProfiles("test")
class EmailAccountIntegrationTest {
    @Autowired EmailAccountService accounts;
    @Autowired AppUserRepository users;
    @Autowired EmailOtpRepository otps;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired AdminService admin;
    @MockitoBean JavaMailSender sender;
    @MockitoBean Clock clock;
    final List<SimpleMailMessage> messages=new CopyOnWriteArrayList<>();
    final AtomicReference<Instant> time=new AtomicReference<>();
    String email,ip;
    static final String PASSWORD="Strong@Test2026", NEW_PASSWORD="Changed@Test2026";
    @BeforeEach void prepare() {
        time.set(Instant.parse("2026-10-29T10:00:00Z"));when(clock.instant()).thenAnswer(x->time.get());when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        messages.clear();doAnswer(x->{messages.add(new SimpleMailMessage(x.getArgument(0,SimpleMailMessage.class)));return null;}).when(sender).send(any(SimpleMailMessage.class));
        String id=UUID.randomUUID().toString();email="test-"+id+"@example.test";ip=id;
    }
    void advance(long seconds) {time.updateAndGet(t->t.plusSeconds(seconds));}
    AppUser user() {return users.findByEmail(email).orElseThrow();}
    String code() {var match=Pattern.compile("(?<![0-9])[0-9]{8}(?![0-9])").matcher(messages.getLast().getText());assertThat(match.find()).isTrue();return match.group();}
    void register() {accounts.register("Người dùng",email,PASSWORD,PASSWORD,ip);}
    void verified() {register();accounts.verifyEmail(email,code(),ip);}
    String grant() {advance(61);accounts.requestReset(email,ip);return accounts.verifyReset(email,code(),ip);}
    Actor actor(AppUser u) {return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    @Test void registrationNormalizesHashesAndOnlyCreatesPendingUser() throws Exception {
        mvc.perform(post("/register").with(csrf()).with(r->{r.setRemoteAddr(ip);return r;})
            .param("fullName","Người dùng").param("email","  "+email.toUpperCase(Locale.ROOT)+"  ")
            .param("password",PASSWORD).param("confirmPassword",PASSWORD).param("role","ADMIN").param("active","true"))
            .andExpect(status().isOk()).andExpect(view().name("verify-email")).andExpect(header().doesNotExist("Set-Cookie"));
        var u=user();assertThat(u.getRole()).isEqualTo(Role.USER);assertThat(u.isActive()).isFalse();assertThat(u.isEmailVerified()).isFalse();assertThat(u.getBranch()).isNull();assertThat(encoder.matches(PASSWORD,u.getPasswordHash())).isTrue();
        var otp=otps.findByUserIdAndPurpose(u.getId(),Purpose.VERIFY_EMAIL).orElseThrow();
        assertThat(otp.getCodeHash()).hasSize(64).doesNotContain(code());
        assertThat(otp.getExpiresAt()).isEqualTo(OffsetDateTime.ofInstant(time.get().plusSeconds(300),ZoneOffset.UTC));
        mvc.perform(post("/login").with(csrf()).param("username",email).param("password",PASSWORD)).andExpect(redirectedUrl("/login?error"));
    }
    @Test void duplicateNormalizedEmailRejected() {register();assertThatThrownBy(()->accounts.register("Other",email.toUpperCase(Locale.ROOT),PASSWORD,PASSWORD,ip)).isInstanceOf(BusinessException.class);assertThat(messages).hasSize(1);}
    @Test void invalidEmailNamePasswordAndConfirmationRejected() {
        assertThatThrownBy(()->accounts.register("A","invalid",PASSWORD,PASSWORD,ip)).isInstanceOf(BusinessException.class);
        for(String invalid:List.of("a..b@example.test","a@example..test",".a@example.test"))
            assertThatThrownBy(()->accounts.register("A",invalid,PASSWORD,PASSWORD,ip)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->accounts.register(" ",email,PASSWORD,PASSWORD,ip)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->accounts.register("A",email,"short","short",ip)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->accounts.register("A",email,PASSWORD,"different",ip)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->accounts.register("A",email,"ấ".repeat(30),"ấ".repeat(30),ip)).isInstanceOf(BusinessException.class);
        assertThat(users.findByEmail(email)).isEmpty();
    }
    @Test void correctVerificationActivatesUserAndPreventsReplay() throws Exception {
        register();String otp=code();accounts.verifyEmail(email,otp,ip);
        assertThat(user().isEmailVerified()).isTrue();assertThat(user().isActive()).isTrue();
        assertThatThrownBy(()->accounts.verifyEmail(email,otp,ip)).isInstanceOf(BusinessException.class);
        mvc.perform(post("/login").with(csrf()).param("username",email.toUpperCase(Locale.ROOT)).param("password",PASSWORD)).andExpect(redirectedUrl("/account")).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("HttpOnly")));
        Cookie cookie=new Cookie("UT_ACCESS",jwt.staff(user()));
        mvc.perform(get("/account").cookie(cookie)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(email)));
        mvc.perform(get("/account/password").cookie(cookie)).andExpect(status().isOk());
        for(String path:List.of("/admin/menu","/staff","/staff/tables")) mvc.perform(get(path).cookie(cookie)).andExpect(status().isForbidden());
        // Guest routes intentionally select UT_GUEST, so a USER cookie cannot authenticate them.
        mvc.perform(get("/guest/menu").cookie(cookie)).andExpect(redirectedUrl("/login?expired"));
    }
    @Test void fiveWrongAttemptsPersistAndExhaustOtp() {
        register();String valid=code();String wrong=valid.equals("00000000")?"11111111":"00000000";
        for(int i=0;i<5;i++) assertThatThrownBy(()->accounts.verifyEmail(email,wrong,ip)).isInstanceOf(BusinessException.class);
        var otp=otps.findByUserIdAndPurpose(user().getId(),Purpose.VERIFY_EMAIL).orElseThrow();assertThat(otp.getAttempts()).isEqualTo(5);assertThat(otp.isConsumed()).isTrue();
        advance(901);assertThatThrownBy(()->accounts.verifyEmail(email,valid,ip)).isInstanceOf(BusinessException.class);
    }
    @Test void otpExpiresAtFiveMinutes() {register();String otp=code();advance(300);assertThatThrownBy(()->accounts.verifyEmail(email,otp,ip)).isInstanceOf(BusinessException.class);assertThat(user().isEmailVerified()).isFalse();}
    @Test void resendEnforcesCooldownAndInvalidatesOldCode() {
        register();String old=code();assertThatThrownBy(()->accounts.resendVerification(email,ip)).isInstanceOf(BusinessException.class);assertThat(messages).hasSize(1);
        advance(61);accounts.resendVerification(email,ip);String fresh=code();assertThat(messages).hasSize(2);
        if(!old.equals(fresh)) assertThatThrownBy(()->accounts.verifyEmail(email,old,ip)).isInstanceOf(BusinessException.class);
        accounts.verifyEmail(email,fresh,ip);assertThat(user().isEmailVerified()).isTrue();
    }
    @Test void accountEmailAndIpSendLimitsApply() {
        register();for(int i=0;i<4;i++) {advance(61);accounts.resendVerification(email,ip);}
        advance(61);assertThatThrownBy(()->accounts.resendVerification(email,ip)).isInstanceOf(BusinessException.class);assertThat(messages).hasSize(5);
        String crowdedIp=UUID.randomUUID().toString();for(int i=0;i<20;i++) accounts.requestReset("missing"+i+"@example.test",crowdedIp);
        assertThatThrownBy(()->accounts.register("A","new"+email,PASSWORD,PASSWORD,crowdedIp)).isInstanceOf(BusinessException.class);
    }
    @Test void smtpFailureKeepsPendingAccountAndInvalidatesUndeliveredOtp() {
        doThrow(new MailSendException("simulated SMTP failure")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(this::register).isInstanceOf(BusinessException.class).hasMessageNotContaining("simulated");
        assertThat(user().isActive()).isFalse();assertThat(otps.findByUserIdAndPurpose(user().getId(),Purpose.VERIFY_EMAIL).orElseThrow().isConsumed()).isTrue();
    }
    @Test void recoveryRequestDoesNotDiscloseUnknownOrUnverifiedEmail() throws Exception {
        register();int sent=messages.size();
        for(String value:List.of(email,"missing-"+email)) mvc.perform(post("/forgot-password").with(csrf()).param("email",value)).andExpect(status().isOk()).andExpect(model().attribute("success",EmailAccountService.RECOVERY_MESSAGE));
        assertThat(messages).hasSize(sent);
        verifiedOtherAndCheckRecoveryOutcome();
    }
    private void verifiedOtherAndCheckRecoveryOutcome() throws Exception {
        accounts.verifyEmail(email,code(),ip);advance(61);
        mvc.perform(post("/forgot-password").with(csrf()).param("email",email)).andExpect(status().isOk()).andExpect(model().attribute("success",EmailAccountService.RECOVERY_MESSAGE));
        assertThat(messages).hasSize(2);
    }
    @Test void resetRequiresPurposeSpecificOtpAndServerGrant() throws Exception {
        register();String registrationCode=code();
        assertThatThrownBy(()->accounts.verifyReset(email,registrationCode,ip)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->accounts.resetPassword(null,NEW_PASSWORD,NEW_PASSWORD)).isInstanceOf(BusinessException.class);
        mvc.perform(get("/reset-password")).andExpect(view().name("forgot-password"));
        mvc.perform(post("/reset-password").with(csrf()).param("password",NEW_PASSWORD).param("confirmPassword",NEW_PASSWORD)).andExpect(view().name("reset-password"));
        assertThat(encoder.matches(PASSWORD,user().getPasswordHash())).isTrue();
    }
    @Test void resetUpdatesHashRevokesJwtAndGrantAndSendsNotification() throws Exception {
        verified();var old=user();String oldJwt=jwt.staff(old);String grant=grant();assertThat(accounts.hasResetGrant(grant)).isTrue();
        accounts.resetPassword(grant,NEW_PASSWORD,NEW_PASSWORD);
        var updated=user();assertThat(encoder.matches(NEW_PASSWORD,updated.getPasswordHash())).isTrue();assertThat(encoder.matches(PASSWORD,updated.getPasswordHash())).isFalse();assertThat(updated.getTokenVersion()).isEqualTo(old.getTokenVersion()+1);
        assertThat(accounts.hasResetGrant(grant)).isFalse();assertThatThrownBy(()->accounts.resetPassword(grant,NEW_PASSWORD,NEW_PASSWORD)).isInstanceOf(BusinessException.class);
        assertThat(messages.getLast().getSubject()).contains("Mật khẩu đã thay đổi");assertThat(messages.getLast().getText()).doesNotContain(NEW_PASSWORD,PASSWORD);
        mvc.perform(get("/account").cookie(new Cookie("UT_ACCESS",oldJwt))).andExpect(redirectedUrl("/login?expired"));
        mvc.perform(post("/login").with(csrf()).param("username",email).param("password",PASSWORD)).andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").with(csrf()).param("username",email).param("password",NEW_PASSWORD)).andExpect(redirectedUrl("/account"));
    }
    @Test void resetOtpAndGrantCannotBeReplayedAndGrantExpires() {
        verified();advance(61);accounts.requestReset(email,ip);String otp=code();String grant=accounts.verifyReset(email,otp,ip);
        assertThatThrownBy(()->accounts.verifyReset(email,otp,ip)).isInstanceOf(BusinessException.class);
        advance(300);assertThat(accounts.hasResetGrant(grant)).isFalse();assertThatThrownBy(()->accounts.resetPassword(grant,NEW_PASSWORD,NEW_PASSWORD)).isInstanceOf(BusinessException.class);
    }
    @Test void newResetRequestInvalidatesPriorGrant() {verified();String old=grant();advance(61);accounts.requestReset(email,ip);assertThat(accounts.hasResetGrant(old)).isFalse();}
    @Test void passwordChangeOrLogoutInvalidatesIssuedResetGrant() {
        verified();String grant=grant();admin.password(actor(user()),PASSWORD,NEW_PASSWORD);
        assertThat(accounts.hasResetGrant(grant)).isFalse();assertThatThrownBy(()->accounts.resetPassword(grant,PASSWORD,PASSWORD)).isInstanceOf(BusinessException.class);
        advance(61);accounts.requestReset(email,ip);String another=accounts.verifyReset(email,code(),ip);admin.logout(actor(user()));assertThat(accounts.hasResetGrant(another)).isFalse();
    }
    @Test void resetWrongAndExpiredOtpNeverIssueGrant() {
        verified();advance(61);accounts.requestReset(email,ip);String otp=code();
        String wrong=otp.equals("00000000")?"11111111":"00000000";
        assertThatThrownBy(()->accounts.verifyReset(email,wrong,ip)).isInstanceOf(BusinessException.class);
        advance(300);assertThatThrownBy(()->accounts.verifyReset(email,otp,ip)).isInstanceOf(BusinessException.class);
        assertThat(otps.findByUserIdAndPurpose(user().getId(),Purpose.RESET_PASSWORD).orElseThrow().getResetHash()).isNull();
    }
    @Test void resendIsSerializedUnderConcurrentRequests() throws Exception {
        register();advance(61);assertOneSuccess(()->accounts.resendVerification(email,ip));assertThat(messages).hasSize(2);
    }
    @Test void existingStaffMustProvePasswordAndVerifyRecoveryEmail() throws Exception {
        var staff=new AppUser();staff.setUsername("staff_"+UUID.randomUUID());staff.setFullName("Nhân viên");staff.setRole(Role.ADMIN);staff.setPasswordHash(encoder.encode(PASSWORD));users.saveAndFlush(staff);Actor actor=actor(staff);
        assertThat(staff.getEmail()).isNull();
        assertThatThrownBy(()->accounts.requestLink(actor,email,"wrong",ip)).isInstanceOf(BusinessException.class);
        accounts.requestLink(actor,email,PASSWORD,ip);String otp=code();assertThat(users.findById(staff.getId()).orElseThrow().getEmail()).isNull();
        accounts.verifyLink(actor,otp,ip);var linked=user();assertThat(linked.isEmailVerified()).isTrue();assertThat(linked.getRole()).isEqualTo(Role.ADMIN);
        String grant=grant();accounts.resetPassword(grant,NEW_PASSWORD,NEW_PASSWORD);assertThat(encoder.matches(NEW_PASSWORD,user().getPasswordHash())).isTrue();
    }
    @Test void linkingNewEmailKeepsOldEmailUntilVerifiedAndInvalidatesOldRecovery() {
        verified();var original=user();String oldGrant=grant();String newEmail="new-"+email;advance(61);
        accounts.requestLink(actor(original),newEmail,PASSWORD,ip);assertThat(user().getEmail()).isEqualTo(email);
        accounts.verifyLink(actor(original),code(),ip);assertThat(users.findByEmail(email)).isEmpty();assertThat(users.findByEmail(newEmail).orElseThrow().isEmailVerified()).isTrue();assertThat(accounts.hasResetGrant(oldGrant)).isFalse();
    }
    @Test void verificationAndResetAreSingleUseUnderConcurrentRequests() throws Exception {
        register();String otp=code();assertOneSuccess(()->accounts.verifyEmail(email,otp,ip));
        String grant=grant();assertOneSuccess(()->accounts.resetPassword(grant,NEW_PASSWORD,NEW_PASSWORD));
    }
    void assertOneSuccess(Runnable task) throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> action=()->{start.await();try {task.run();return true;}catch(BusinessException e){return false;}};
        try {var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        finally {pool.shutdownNow();}
    }
    @Test void csrfRequiredOnAllNewPostEndpointsAndPagesRender() throws Exception {
        for(String path:List.of("/register","/verify-email","/verify-email/resend","/forgot-password","/forgot-password/verify","/reset-password","/account/email","/account/email/verify")) mvc.perform(post(path)).andExpect(status().isForbidden());
        for(String path:List.of("/register","/verify-email","/forgot-password")) mvc.perform(get(path)).andExpect(status().isOk());
    }
    @Test void resetCookieIsHttpOnlyScopedAndNotAnAuthenticationCookie() throws Exception {
        verified();advance(61);accounts.requestReset(email,ip);
        var response=mvc.perform(post("/forgot-password/verify").with(csrf()).with(r->{r.setRemoteAddr(ip);return r;}).param("email",email).param("code",code()))
            .andExpect(redirectedUrl("/reset-password")).andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).contains("UT_RESET=","HttpOnly","SameSite=Strict","Path=/reset-password","Max-Age=300").doesNotContain("UT_ACCESS=");
    }
}
