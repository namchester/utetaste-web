package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="dishes") @Getter @Setter
public class Dish extends BaseEntity {
@Column(nullable=false,length=120) private String name;
@Column(nullable=false,length=1000) private String description;
@Column(nullable=false,precision=14,scale=0) private BigDecimal price;
@ManyToOne(optional=false) @JoinColumn(name="category_id",nullable=false) private Category category;
@Column(nullable=false,length=500) private String imageUrl = "/images/food/chicken.jpg";
@Column(nullable=false) private boolean active = true;
@Column(length=255) private String imagePublicId;
}
