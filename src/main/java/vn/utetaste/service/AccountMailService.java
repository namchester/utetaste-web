package vn.utetaste.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import vn.utetaste.domain.EmailOtp.Purpose;

@Service
public class AccountMailService {
    private final JavaMailSender sender;
    private final String from;
    private final boolean enabled;
    public AccountMailService(JavaMailSender sender,@Value("${app.mail.from}") String from,
            @Value("${app.mail.enabled}") boolean enabled) {
        this.sender=sender;this.from=from;this.enabled=enabled;
    }
    public void otp(String email,String code,Purpose purpose) {
        String action=switch(purpose) {
            case VERIFY_EMAIL -> "xác minh tài khoản";
            case LINK_EMAIL -> "liên kết email khôi phục";
            case RESET_PASSWORD -> "khôi phục mật khẩu";
        };
        send(email,"UTE Taste — Mã "+action,"Mã "+action+" của bạn: "+code+
            "\nMã có hiệu lực 5 phút, chỉ dùng một lần. Không chia sẻ mã với người khác.\nNếu không yêu cầu, hãy bỏ qua email này.");
    }
    public void passwordChanged(String email) {
        send(email,"UTE Taste — Mật khẩu đã thay đổi","Mật khẩu tài khoản UTE Taste của bạn vừa được thay đổi. Các phiên đăng nhập cũ đã bị thu hồi. Nếu không phải bạn, hãy liên hệ quản trị viên.");
    }
    private void send(String email,String subject,String text) {
        if(!enabled||from.isBlank()) throw new BusinessException("Dịch vụ email chưa sẵn sàng. Vui lòng thử lại sau hoặc liên hệ quản trị viên.");
        var message=new SimpleMailMessage();message.setFrom(from);message.setTo(email);message.setSubject(subject);message.setText(text);
        // Never log this message, its body, or the SMTP exception (may include credentials).
        sender.send(message);
    }
}
