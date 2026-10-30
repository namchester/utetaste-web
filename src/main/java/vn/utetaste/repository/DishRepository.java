package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface DishRepository extends JpaRepository<Dish,Long>, JpaSpecificationExecutor<Dish> {
List<Dish> findAllByOrderByNameAsc();
@Query("select count(d) from Dish d where d.active=true and (:branch is null or exists(select bd.id from BranchDish bd where bd.dish=d and bd.branch.id=:branch and bd.available=true))")
long sellingCount(@Param("branch") Long branch);
long countByCategoryId(Long categoryId);
interface CategoryCount {Long getCategoryId();long getDishCount();}
@Query("select d.category.id as categoryId,count(d) as dishCount from Dish d group by d.category.id")
List<CategoryCount> categoryCounts();
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select d from Dish d where d.id=:id")
Optional<Dish> lockById(@Param("id") Long id);
@Modifying(flushAutomatically=true,clearAutomatically=true)
@Query("update Dish d set d.imageUrl=:url,d.imagePublicId=:publicId where d.id=:id")
int updateImage(@Param("id") Long id,@Param("url") String url,@Param("publicId") String publicId);
}
