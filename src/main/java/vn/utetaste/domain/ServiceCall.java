package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="service_calls") @Getter @Setter
public class ServiceCall extends BaseEntity {
@ManyToOne(optional=false) @JoinColumn(name="session_id",nullable=false) private DiningSession session;
@Column(nullable=false,length=120) private String message;
@Column(nullable=false) private OffsetDateTime createdAt = OffsetDateTime.now();
private OffsetDateTime resolvedAt;
}
