package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="audit_logs") @Getter @Setter
public class AuditLog extends BaseEntity {
@Column(nullable=false,length=100) private String actor;
@Column(nullable=false,length=80) private String action;
@Column(nullable=false,length=500) private String detail;
private Long branchId;
@Column(nullable=false) private OffsetDateTime createdAt = OffsetDateTime.now();
}
