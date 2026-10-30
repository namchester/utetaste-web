package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface DiningSessionRepository extends JpaRepository<DiningSession,Long> {
@Query("select count(s) from DiningSession s where s.closedAt is null and (:branch is null or s.table.branch.id=:branch)")
long openCount(@Param("branch") Long branch);
Optional<DiningSession> findByTableIdAndClosedAtIsNull(Long tableId);
List<DiningSession> findByClosedAtIsNull();
@EntityGraph(attributePaths={"table","table.branch"})
List<DiningSession> findByTableBranchIdAndClosedAtIsNull(Long branchId);
@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from DiningSession s where s.id=:id") Optional<DiningSession> lockById(@Param("id") Long id);
}
