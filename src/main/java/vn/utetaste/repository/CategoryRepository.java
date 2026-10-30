package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface CategoryRepository extends JpaRepository<Category,Long> {
List<Category> findAllByOrderBySortOrderAscNameAsc();
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from Category c where c.id=:id")
Optional<Category> lockById(@Param("id") Long id);
}
