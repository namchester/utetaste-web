package vn.utetaste.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.utetaste.domain.EmailOtp;

public interface EmailOtpRepository extends JpaRepository<EmailOtp,Long> {
    Optional<EmailOtp> findByUserIdAndPurpose(Long userId, EmailOtp.Purpose purpose);
    Optional<EmailOtp> findByResetHash(String resetHash);
}
