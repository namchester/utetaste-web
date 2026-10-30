package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="branch_dishes", uniqueConstraints=@UniqueConstraint(columnNames={"branch_id","dish_id"})) @Getter @Setter
public class BranchDish extends BaseEntity {
@ManyToOne(optional=false) @JoinColumn(name="branch_id",nullable=false) private Branch branch;
@ManyToOne(optional=false) @JoinColumn(name="dish_id",nullable=false) private Dish dish;
@Column(nullable=false) private boolean available = true;
}
