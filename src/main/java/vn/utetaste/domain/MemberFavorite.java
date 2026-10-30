package vn.utetaste.domain;
import java.time.OffsetDateTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="member_favorites",uniqueConstraints=@UniqueConstraint(name="uq_member_favorite",columnNames={"user_id","dish_id"})) @Getter @Setter
public class MemberFavorite extends BaseEntity {
    @ManyToOne(optional=false,fetch=FetchType.LAZY) @JoinColumn(name="user_id",nullable=false) private AppUser user;
    @ManyToOne(optional=false) @JoinColumn(name="dish_id",nullable=false) private Dish dish;
    @Column(nullable=false) private OffsetDateTime createdAt=OffsetDateTime.now();
}
