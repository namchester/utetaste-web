package vn.utetaste.repository;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vn.utetaste.domain.MemberFavorite;
public interface MemberFavoriteRepository extends JpaRepository<MemberFavorite,Long> {
    Optional<MemberFavorite> findByUserIdAndDishId(Long userId,Long dishId);
    long countByUserId(Long userId);
    @EntityGraph(attributePaths={"dish","dish.category"})
    Page<MemberFavorite> findByUserIdOrderByCreatedAtDescIdDesc(Long userId,Pageable pageable);
    @Query("select f.dish.id from MemberFavorite f where f.user.id=:user and f.dish.id in :ids")
    Set<Long> selected(@Param("user") Long user,@Param("ids") List<Long> ids);
}
