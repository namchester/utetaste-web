package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="categories") @Getter @Setter
public class Category extends BaseEntity {
@Column(nullable=false,unique=true,length=80) private String name;
@Column(nullable=false) private int sortOrder;
}
