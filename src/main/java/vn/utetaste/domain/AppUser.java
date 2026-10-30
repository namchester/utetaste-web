package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="app_users") @Getter @Setter
public class AppUser extends BaseEntity {
@Column(nullable=false,unique=true,length=80) private String username;
@Column(nullable=false,length=100) private String passwordHash;
@Column(nullable=false,length=100) private String fullName;
@Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Role role;
@ManyToOne @JoinColumn(name="branch_id") private Branch branch;
@Column(nullable=false) private boolean active = true;
@Column(nullable=false) private int tokenVersion = 0;
@Column(unique=true,length=254) private String email;
@Column(nullable=false) private boolean emailVerified = false;
private OffsetDateTime mailWindow;
private OffsetDateTime lastMailSentAt;
@Column(nullable=false) private int mailSends = 0;
@Column(length=20) private String phone;
private OffsetDateTime createdAt = OffsetDateTime.now();
}
