package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="restaurant_tables", uniqueConstraints=@UniqueConstraint(columnNames={"branch_id","label"})) @Getter @Setter
public class RestaurantTable extends BaseEntity {
@ManyToOne(optional=false) @JoinColumn(name="branch_id",nullable=false) private Branch branch;
@Column(nullable=false,length=30) private String label;
@Column(nullable=false) private int capacity;
@Column(nullable=false,unique=true,length=36) private String qrKey = UUID.randomUUID().toString();
@Column(nullable=false) private boolean active = true;
}
