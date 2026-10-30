package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="food_orders", uniqueConstraints=@UniqueConstraint(columnNames={"session_id","request_key"})) @Getter @Setter
public class FoodOrder extends BaseEntity {
@ManyToOne(optional=false) @JoinColumn(name="session_id",nullable=false) private DiningSession session;
// Assigned only on insertion from a verified member cookie; legacy/anonymous orders stay NULL.
@ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="customer_id",updatable=false) private AppUser customer;
@Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private OrderStatus status = OrderStatus.NEW;
@Column(nullable=false,length=500) private String note = "";
@Column(nullable=false,length=36) private String requestKey;
@Column(nullable=false) private OffsetDateTime createdAt = OffsetDateTime.now();
@Column(nullable=false) private OffsetDateTime updatedAt = OffsetDateTime.now();
@Version private Long version;
@OneToMany(mappedBy="order",cascade=CascadeType.ALL,orphanRemoval=true) @OrderBy("id") private List<OrderItem> items = new ArrayList<>();
public BigDecimal getTotal() { return items.stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO,BigDecimal::add); }
public String getCode() { return "UT" + String.format("%06d", getId()); }
}
