package vn.utetaste.repository;
import vn.utetaste.domain.*;
import vn.utetaste.service.Reports;
import java.util.*;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface FoodOrderRepository extends JpaRepository<FoodOrder,Long>, JpaSpecificationExecutor<FoodOrder> {
boolean existsBySessionIdAndCustomerIsNotNull(Long sessionId);
@EntityGraph(attributePaths={"items"}) List<FoodOrder> findBySessionIdAndCustomerIsNullOrderByCreatedAtAscIdAsc(Long sessionId);
@EntityGraph(attributePaths={"items","session","session.table","session.table.branch"})
Optional<FoodOrder> findByIdAndCustomerId(Long id,Long customerId);
@EntityGraph(attributePaths={"items","session","session.table","session.table.branch"})
List<FoodOrder> findByCustomerIdAndSessionIdOrderByCreatedAtAscIdAsc(Long customerId,Long sessionId);
@Query("select count(o) from FoodOrder o where o.customer.id=:user and o.status<>vn.utetaste.domain.OrderStatus.CANCELLED and exists(select i.id from Invoice i where i.session.id=o.session.id)")
long paidMemberOrders(@Param("user") Long user);
@Query("select distinct o from FoodOrder o left join fetch o.items where o.id in :ids")
List<FoodOrder> fetchItems(@Param("ids") List<Long> ids);
interface StatusCount {OrderStatus getStatus();long getCount();}
interface SessionTotal {Long getSessionId();long getOrderCount();java.math.BigDecimal getTotal();}
@Query("select o.session.id as sessionId,count(distinct o.id) as orderCount,sum(i.unitPrice*cast(i.quantity as java.math.BigDecimal)) as total from FoodOrder o join o.items i where o.session.table.branch.id=:branch and o.session.closedAt is null and o.status<>vn.utetaste.domain.OrderStatus.CANCELLED group by o.session.id")
List<SessionTotal> openSessionTotals(@Param("branch") Long branch);
@Query("select o.status as status,count(o) as count from FoodOrder o where (:branch is null or o.session.table.branch.id=:branch) and o.createdAt>=:start and o.createdAt<:end group by o.status")
List<StatusCount> statusCounts(@Param("branch") Long branch,@Param("start") OffsetDateTime start,@Param("end") OffsetDateTime end);
@EntityGraph(attributePaths={"items"}) List<FoodOrder> findBySessionIdOrderByCreatedAt(Long sessionId);
@EntityGraph(attributePaths={"items"}) Optional<FoodOrder> findBySessionIdAndRequestKey(Long sessionId,String requestKey);
@EntityGraph(attributePaths={"items"}) List<FoodOrder> findTop100BySessionTableBranchIdOrderByCreatedAtDesc(Long branchId);
@EntityGraph(attributePaths={"items"}) List<FoodOrder> findBySessionTableBranchIdAndSessionClosedAtIsNullOrderByCreatedAtAsc(Long branchId);
/** BUG-01 fix: aggregated top-dishes query – replaces N+1 nested loops in Reports.top() */
@Query("SELECT new vn.utetaste.service.Reports$TopDish(oi.dishName, CAST(SUM(oi.quantity) AS int), SUM(oi.unitPrice * CAST(oi.quantity AS java.math.BigDecimal))) " +
       "FROM OrderItem oi JOIN oi.order o " +
       "WHERE o.session.id IN :sessionIds AND o.status <> vn.utetaste.domain.OrderStatus.CANCELLED " +
       "GROUP BY oi.dishName ORDER BY SUM(oi.quantity) DESC LIMIT 10")
List<Reports.TopDish> findTopDishes(@Param("sessionIds") List<Long> sessionIds);
}
