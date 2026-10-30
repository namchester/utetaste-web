package vn.utetaste.domain;
import java.time.OffsetDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="member_point_entries") @org.hibernate.annotations.Check(constraints="points <> 0") @Getter @Setter
public class MemberPointEntry extends BaseEntity {
    @ManyToOne(optional=false,fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private AppUser user;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="invoice_id") private Invoice invoice;
    @Column(nullable=false,unique=true,length=100) private String referenceKey;
    @Column(nullable=false) private int points;
    @Column(nullable=false,length=200) private String reason;
    @Column(nullable=false) private OffsetDateTime createdAt=OffsetDateTime.now();
}
