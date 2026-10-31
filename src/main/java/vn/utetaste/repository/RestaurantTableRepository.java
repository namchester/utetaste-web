package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface RestaurantTableRepository extends JpaRepository<RestaurantTable,Long> {
@Query("select count(t) from RestaurantTable t where t.active=true and t.branch.active=true and (:branch is null or t.branch.id=:branch)")
long activeCount(@Param("branch") Long branch);
@EntityGraph(attributePaths={"branch"}) List<RestaurantTable> findByBranchIdOrderByLabel(Long branchId);
Optional<RestaurantTable> findByQrKey(String qrKey);
@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from RestaurantTable t where t.id = :id") Optional<RestaurantTable> lockById(@Param("id") Long id);
boolean existsByBranchId(Long branchId);
}
