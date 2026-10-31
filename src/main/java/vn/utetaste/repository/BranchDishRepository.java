package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface BranchDishRepository extends JpaRepository<BranchDish,Long> {
@Query("select bd.dish.id from BranchDish bd where bd.branch.id=:branch and bd.branch.active=true and bd.available=true and bd.dish.active=true and bd.dish.id in :ids")
Set<Long> availableIds(@Param("branch") Long branch,@Param("ids") List<Long> ids);
List<BranchDish> findByBranchIdOrderByDishName(Long branchId);
Optional<BranchDish> findByBranchIdAndDishId(Long branchId, Long dishId);
@Modifying(flushAutomatically=true)
@Query("delete from BranchDish b where b.branch.id=:id")
int removeForBranch(@Param("id") Long id);
@Modifying(flushAutomatically=true)
@Query("delete from BranchDish b where b.dish.id=:id")
int removeForDish(@Param("id") Long id);
}
