package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="order_items") @Getter @Setter
public class OrderItem extends BaseEntity {
@ManyToOne(optional=false,fetch=FetchType.LAZY) @JoinColumn(name="order_id",nullable=false) private FoodOrder order;
@ManyToOne(optional=false) @JoinColumn(name="dish_id",nullable=false) private Dish dish;
@Column(nullable=false,length=120) private String dishName;
@Column(nullable=false,precision=14,scale=0) private BigDecimal unitPrice;
@Column(nullable=false) private int quantity;
public BigDecimal getLineTotal() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
}
