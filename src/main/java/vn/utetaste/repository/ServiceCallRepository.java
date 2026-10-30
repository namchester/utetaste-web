package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ServiceCallRepository extends JpaRepository<ServiceCall,Long> {
@Query("select c from ServiceCall c join fetch c.session s join fetch s.table t join fetch t.branch where c.resolvedAt is null and (:branch is null or t.branch.id=:branch) order by c.createdAt")
List<ServiceCall> pending(@Param("branch") Long branch,org.springframework.data.domain.Pageable page);
@Query("select count(c) from ServiceCall c where c.resolvedAt is null and (:branch is null or c.session.table.branch.id=:branch)")
long pendingCount(@Param("branch") Long branch);
List<ServiceCall> findBySessionTableBranchIdAndResolvedAtIsNullOrderByCreatedAt(Long branchId);
Optional<ServiceCall> findFirstBySessionIdAndResolvedAtIsNull(Long sessionId);
}
