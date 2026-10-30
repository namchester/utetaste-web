package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface AuditLogRepository extends JpaRepository<AuditLog,Long>, JpaSpecificationExecutor<AuditLog> {
List<AuditLog> findTop100ByOrderByCreatedAtDesc();
List<AuditLog> findByDetailAndActionInOrderByCreatedAtAscIdAsc(String detail,List<String> actions);
}
