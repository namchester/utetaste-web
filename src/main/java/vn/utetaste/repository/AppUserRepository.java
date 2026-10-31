package vn.utetaste.repository;
import vn.utetaste.domain.*;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface AppUserRepository extends JpaRepository<AppUser,Long>, JpaSpecificationExecutor<AppUser> {
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select u from AppUser u where u.role=vn.utetaste.domain.Role.ADMIN order by u.id")
List<AppUser> lockAdmins();
long countByRoleAndActiveTrue(Role role);
Optional<AppUser> findByUsername(String username);
Optional<AppUser> findByEmail(String email);
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select u from AppUser u where u.id=:id")
Optional<AppUser> lockById(@Param("id") Long id);
List<AppUser> findAllByOrderById();
@Query("select count(u) from AppUser u where u.active=true and u.role in :roles and (:branch is null or u.branch.id=:branch)")
long activeStaff(@Param("branch") Long branch,@Param("roles") List<Role> roles);
boolean existsByBranchId(Long branchId);
}
