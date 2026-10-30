package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="invoices") @Getter @Setter
public class Invoice extends BaseEntity {
@OneToOne(optional=false) @JoinColumn(name="session_id",nullable=false,unique=true) private DiningSession session;
@Column(nullable=false,precision=14,scale=0) private BigDecimal subtotal;
@Column(nullable=false,precision=14,scale=0) private BigDecimal discountAmount;
@Column(nullable=false,precision=14,scale=0) private BigDecimal total;
@Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private PaymentMethod method;
@Column(nullable=false) private OffsetDateTime paidAt = OffsetDateTime.now();
@Column(nullable=false,length=100) private String cashier;
public String getCode() { return "HD" + String.format("%06d",getId()); }
}
