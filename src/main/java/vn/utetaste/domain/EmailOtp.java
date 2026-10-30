package vn.utetaste.domain;

import java.time.OffsetDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="email_otps", uniqueConstraints=@UniqueConstraint(columnNames={"user_id","purpose"}))
@Getter @Setter
public class EmailOtp extends BaseEntity {
    public enum Purpose { VERIFY_EMAIL, LINK_EMAIL, RESET_PASSWORD }
    @ManyToOne(optional=false) @JoinColumn(name="user_id",nullable=false) private AppUser user;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=30) private Purpose purpose;
    @Column(nullable=false,length=254) private String targetEmail;
    @Column(nullable=false,length=64) private String codeHash;
    @Column(nullable=false) private OffsetDateTime expiresAt;
    @Column(nullable=false) private int attempts;
    @Column(nullable=false) private boolean consumed;
    @Column(nullable=false) private int issuedVersion;
    @Column(unique=true,length=64) private String resetHash;
    private OffsetDateTime resetExpiresAt;
}
