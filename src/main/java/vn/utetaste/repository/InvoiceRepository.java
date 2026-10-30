package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface InvoiceRepository extends JpaRepository<Invoice,Long> {
@Query("select i from Invoice i join fetch i.session s join fetch s.table t join fetch t.branch where (:branch is null or t.branch.id=:branch) and i.paidAt>=:start and i.paidAt<:end order by i.paidAt desc")
List<Invoice> paid(@Param("branch") Long branch,@Param("start") OffsetDateTime start,@Param("end") OffsetDateTime end);
List<Invoice> findBySessionIdIn(List<Long> ids);
Optional<Invoice> findBySessionId(Long sessionId);
List<Invoice> findBySessionTableBranchIdAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtDesc(Long branchId,OffsetDateTime from,OffsetDateTime to);
}
