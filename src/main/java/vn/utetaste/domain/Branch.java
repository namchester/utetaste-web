package vn.utetaste.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
@Entity @Table(name="branches") @Getter @Setter
public class Branch extends BaseEntity {
@Column(nullable=false,length=100) private String name;
@Column(nullable=false,length=255) private String address;
@Column(nullable=false,length=20) private String phone;
@Column(nullable=false) private boolean active = true;
}
