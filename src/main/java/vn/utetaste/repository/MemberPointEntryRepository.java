package vn.utetaste.repository;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vn.utetaste.domain.MemberPointEntry;
public interface MemberPointEntryRepository extends JpaRepository<MemberPointEntry,Long> {
    Page<MemberPointEntry> findByUserIdOrderByCreatedAtDescIdDesc(Long userId,Pageable pageable);
    @Query("select coalesce(sum(e.points),0) from MemberPointEntry e where e.user.id=:user")
    long balance(@Param("user") Long user);
}
