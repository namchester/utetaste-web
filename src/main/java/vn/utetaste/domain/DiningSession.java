package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="dining_sessions") @Getter @Setter
public class DiningSession extends BaseEntity {
@ManyToOne(optional=false) @JoinColumn(name="table_id",nullable=false) private RestaurantTable table;
@Column(nullable=false) private OffsetDateTime openedAt = OffsetDateTime.now();
private OffsetDateTime closedAt;
public boolean isOpen() { return closedAt == null; }
}
