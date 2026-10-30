package vn.utetaste.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.domain.EmailOtp.Purpose;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;

@Service
@Transactional(noRollbackFor=BusinessException.class)
public class EmailAccountService {
    public static final String RECOVERY_MESSAGE="Nếu email đủ điều kiện, mã đã được gửi. Vui lòng kiểm tra hộp thư và thư rác.";
    private final AppUserRepository users;
    private final EmailOtpRepository otps;
    private final PasswordEncoder passwords;
    private final AccountMailService mail;
    private final EmailRateLimiter limits;
    private final Clock clock;
    private final EntityManager em;
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public EmailAccountService(AppUserRepository users,EmailOtpRepository otps,PasswordEncoder passwords,
            AccountMailService mail,EmailRateLimiter limits,Clock clock,EntityManager em,
            @Value("${app.otp-secret}") String secret) {
        this.users=users;this.otps=otps;this.passwords=passwords;this.mail=mail;this.limits=limits;this.clock=clock;this.em=em;
        key=secret.getBytes(StandardCharsets.UTF_8);
        if(key.length<32||secret.startsWith("CHANGE_ME"))
            throw new IllegalStateException("OTP_SECRET phải là khóa ngẫu nhiên ít nhất 32 byte, không dùng giá trị minh họa.");
    }
    public static String normalize(String email) {
        String value=email==null?"":email.strip().toLowerCase(Locale.ROOT);
        if(value.length()>254||!value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}"))
            throw new BusinessException("Địa chỉ email không hợp lệ.");
        try {new InternetAddress(value,true).validate();}
        catch(AddressException e) {throw new BusinessException("Địa chỉ email không hợp lệ.");}
        return value;
    }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private void budget(String action,String email,String ip) {
        limits.take(action+":ip:"+ip,20);
        limits.take(action+":email:"+hash(email),5);
    }
    public void register(String name,String email,String password,String confirmation,String ip) {
        email=normalize(email);budget("send",email,ip);
        if(name==null||name.isBlank()||name.strip().length()>100) throw new BusinessException("Họ tên phải từ 1 đến 100 ký tự.");
        checkPasswords(password,confirmation);
        if(users.findByEmail(email).isPresent()) throw new BusinessException("Email không thể đăng ký. Nếu đã đăng ký, hãy xác minh hoặc khôi phục tài khoản.");
        var u=new AppUser();u.setUsername("user_"+UUID.randomUUID());u.setFullName(name.strip());
        u.setEmail(email);u.setRole(Role.USER);u.setBranch(null);u.setActive(false);u.setEmailVerified(false);
        u.setPasswordHash(passwords.encode(password));users.saveAndFlush(u);
        issue(u,email,Purpose.VERIFY_EMAIL);
    }
    public void resendVerification(String email,String ip) {
        email=normalize(email);budget("send",email,ip);
        var found=users.findByEmail(email);
        if(found.isEmpty()) return;
        var u=lock(found.get().getId());
        if(u.getRole()==Role.USER&&!u.isEmailVerified()) issue(u,email,Purpose.VERIFY_EMAIL);
    }
    public void verifyEmail(String email,String code,String ip) {
        email=normalize(email);budget("verify",email,ip);
        var found=users.findByEmail(email).orElseThrow(EmailAccountService::invalidOtp);
        var u=lock(found.getId());
        if(u.getRole()!=Role.USER||u.isEmailVerified()) throw invalidOtp();
        consume(u,Purpose.VERIFY_EMAIL,email,code);
        u.setEmailVerified(true);u.setActive(true);u.setTokenVersion(u.getTokenVersion()+1);
    }
    public void requestReset(String email,String ip) {
        email=normalize(email);
        // Same public outcome for unknown, unverified, disabled, throttled and SMTP-failed requests.
        try {
            budget("send",email,ip);
            var found=users.findByEmail(email);if(found.isEmpty()) return;
            var u=lock(found.get().getId());
            if(eligible(u)) issue(u,email,Purpose.RESET_PASSWORD);
        } catch(BusinessException ignored) { }
    }
    public String verifyReset(String email,String code,String ip) {
        email=normalize(email);budget("verify",email,ip);
        var found=users.findByEmail(email).orElseThrow(EmailAccountService::invalidOtp);
        var u=lock(found.getId());if(!eligible(u)) throw invalidOtp();
        var otp=consume(u,Purpose.RESET_PASSWORD,email,code);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String grant=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        otp.setResetHash(hash("reset:"+grant));otp.setResetExpiresAt(now().plusMinutes(5));return grant;
    }
    public boolean hasResetGrant(String grant) {
        if(grant==null||grant.length()>100) return false;
        return otps.findByResetHash(hash("reset:"+grant)).filter(o->o.isConsumed()&&o.getIssuedVersion()==o.getUser().getTokenVersion()&&o.getResetExpiresAt()!=null&&o.getResetExpiresAt().isAfter(now())&&eligible(o.getUser())).isPresent();
    }
    public void resetPassword(String grant,String password,String confirmation) {
        checkPasswords(password,confirmation);
        if(grant==null||grant.length()>100) throw new BusinessException("Quyền đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.");
        String digest=hash("reset:"+grant);
        var found=otps.findByResetHash(digest).orElseThrow(()->new BusinessException("Quyền đặt lại mật khẩu không hợp lệ hoặc đã hết hạn."));
        var u=lock(found.getUser().getId());var otp=otps.findByUserIdAndPurpose(u.getId(),Purpose.RESET_PASSWORD).orElseThrow();em.refresh(otp);
        if(!digest.equals(otp.getResetHash())||!otp.isConsumed()||otp.getIssuedVersion()!=u.getTokenVersion()||otp.getResetExpiresAt()==null||!otp.getResetExpiresAt().isAfter(now())||!eligible(u))
            throw new BusinessException("Quyền đặt lại mật khẩu không hợp lệ hoặc đã hết hạn.");
        u.setPasswordHash(passwords.encode(password));u.setTokenVersion(u.getTokenVersion()+1);
        invalidate(u);
        // Failure to send a notification must not undo an already accepted password change.
        try { mail.passwordChanged(u.getEmail()); } catch(RuntimeException ignored) { }
    }
    public void requestLink(Actor actor,String email,String currentPassword,String ip) {
        if(actor==null||actor.id()==null||actor.isGuest()) throw new AccessDeniedException("Cần đăng nhập.");
        email=normalize(email);budget("send",email,ip);var u=lock(actor.id());
        if(currentPassword==null||currentPassword.getBytes(StandardCharsets.UTF_8).length>72||!passwords.matches(currentPassword,u.getPasswordHash()))
            throw new BusinessException("Mật khẩu hiện tại chưa đúng.");
        if(users.findByEmail(email).isPresent()) throw new BusinessException("Không thể liên kết địa chỉ email này.");
        issue(u,email,Purpose.LINK_EMAIL);
    }
    public void verifyLink(Actor actor,String code,String ip) {
        if(actor==null||actor.id()==null||actor.isGuest()) throw new AccessDeniedException("Cần đăng nhập.");
        limits.take("link-verify:"+ip,40);var u=lock(actor.id());
        var pending=otps.findByUserIdAndPurpose(u.getId(),Purpose.LINK_EMAIL).orElseThrow(EmailAccountService::invalidOtp);
        var otp=consume(u,Purpose.LINK_EMAIL,pending.getTargetEmail(),code);
        if(users.findByEmail(otp.getTargetEmail()).isPresent()) throw new BusinessException("Không thể liên kết địa chỉ email này.");
        u.setEmail(otp.getTargetEmail());u.setEmailVerified(true);u.setTokenVersion(u.getTokenVersion()+1);invalidate(u);
    }
    private void issue(AppUser u,String email,Purpose purpose) {
        var time=now();
        if(u.getLastMailSentAt()!=null&&u.getLastMailSentAt().plusSeconds(60).isAfter(time))
            throw new BusinessException("Vui lòng chờ ít nhất 60 giây trước khi gửi lại mã.");
        if(u.getMailWindow()==null||!u.getMailWindow().plusHours(1).isAfter(time)) {u.setMailWindow(time);u.setMailSends(0);}
        if(u.getMailSends()>=5) throw new BusinessException("Đã vượt giới hạn 5 email mỗi giờ cho tài khoản.");
        u.setMailSends(u.getMailSends()+1);u.setLastMailSentAt(time);
        var otp=otps.findByUserIdAndPurpose(u.getId(),purpose).orElseGet(EmailOtp::new);
        String code=String.format(Locale.ROOT,"%08d",random.nextInt(100_000_000));
        otp.setUser(u);otp.setPurpose(purpose);otp.setTargetEmail(email);otp.setCodeHash(hash(u.getId()+":"+purpose+":"+email+":"+code));
        otp.setExpiresAt(time.plusMinutes(5));otp.setAttempts(0);otp.setConsumed(false);otp.setIssuedVersion(u.getTokenVersion());otp.setResetHash(null);otp.setResetExpiresAt(null);otps.saveAndFlush(otp);
        try { mail.otp(email,code,purpose); }
        catch(RuntimeException e) {otp.setConsumed(true);throw new BusinessException("Chưa gửi được email. Vui lòng chờ 60 giây rồi yêu cầu mã mới.");}
    }
    private EmailOtp consume(AppUser u,Purpose purpose,String email,String code) {
        var otp=otps.findByUserIdAndPurpose(u.getId(),purpose).orElseThrow(EmailAccountService::invalidOtp);
        em.refresh(otp);
        if(otp.isConsumed()||otp.getIssuedVersion()!=u.getTokenVersion()||otp.getAttempts()>=5||!otp.getExpiresAt().isAfter(now())||!email.equals(otp.getTargetEmail())) throw invalidOtp();
        String supplied=hash(u.getId()+":"+purpose+":"+email+":"+Objects.toString(code,""));
        if(code==null||!code.matches("[0-9]{8}")||!MessageDigest.isEqual(otp.getCodeHash().getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII))) {
            otp.setAttempts(otp.getAttempts()+1);if(otp.getAttempts()>=5) otp.setConsumed(true);throw invalidOtp();
        }
        otp.setConsumed(true);return otp;
    }
    private AppUser lock(Long id) {var u=users.lockById(id).orElseThrow();em.refresh(u);return u;}
    private boolean eligible(AppUser u) {return u.isActive()&&u.isEmailVerified()&&u.getEmail()!=null&&u.getRole()!=Role.GUEST&&(u.getRole()==Role.ADMIN||u.getRole()==Role.USER||(u.getBranch()!=null&&u.getBranch().isActive()));}
    private void invalidate(AppUser u) { for(var purpose:Purpose.values()) otps.findByUserIdAndPurpose(u.getId(),purpose).ifPresent(o->{o.setConsumed(true);o.setResetHash(null);o.setResetExpiresAt(null);}); }
    private void checkPasswords(String password,String confirmation) {AdminService.validatePassword(password);if(!password.equals(confirmation)) throw new BusinessException("Xác nhận mật khẩu chưa khớp.");}
    private static BusinessException invalidOtp() {return new BusinessException("Mã không hợp lệ, đã hết hạn hoặc đã sử dụng. Hãy yêu cầu mã mới nếu cần.");}
    private String hash(String value) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException e) {throw new IllegalStateException("Không khởi tạo được bộ bảo vệ OTP.");}
    }
}
