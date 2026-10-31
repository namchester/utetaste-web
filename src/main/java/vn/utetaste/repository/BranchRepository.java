package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface BranchRepository extends JpaRepository<Branch,Long> {
List<Branch> findByActiveTrueOrderByName();
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select b from Branch b where b.id=:id")
Optional<Branch> lockById(@Param("id") Long id);
List<Branch> findByNameIgnoreCase(String name);
}
