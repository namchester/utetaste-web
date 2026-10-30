package vn.utetaste.service;

import java.math.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AdminQueries {
    private final InvoiceRepository invoices;private final FoodOrderRepository orders;private final AuditLogRepository audit;
    private final RestaurantTableRepository tables;private final DiningSessionRepository sessions;private final AppUserRepository users;
    private final DishRepository dishes;private final BranchRepository branches;private final OrderService ops;private final AdminCatalog catalog;
    private final ServiceCallRepository calls;
    public record Period(LocalDate from,LocalDate to) {
        public OffsetDateTime start(){return from.atStartOfDay(Reports.ZONE).toOffsetDateTime();}
        public OffsetDateTime end(){return to.plusDays(1).atStartOfDay(Reports.ZONE).toOffsetDateTime();}
    }
    public record StatusTotal(OrderStatus status,long count) {}
    public record BranchRevenue(String name,BigDecimal revenue,long invoices) {}
    public record Dashboard(BigDecimal today,BigDecimal week,BigDecimal month,BigDecimal periodRevenue,long invoiceCount,
        long tableCount,long occupied,long free,long openSessions,long staff,long selling,
        List<StatusTotal> statuses,List<Reports.Day> days7,List<Reports.Day> days30,List<Reports.TopDish> top,
        List<BranchRevenue> branchRevenue,List<AuditLog> activity,long callCount,List<ServiceCall> calls) {}
    public record OrderRow(OrderService.OrderView order,String branch,boolean sessionOpen,boolean paid) {}
    public record OrderDetail(OrderRow row,Long invoiceId,List<AuditLog> history) {}
    public record OrderSearch(Page<OrderRow> page,List<StatusTotal> statuses) {}
    public static Period period(LocalDate from,LocalDate to) {
        if(to==null)to=LocalDate.now(Reports.ZONE);if(from==null)from=to.minusDays(29);
        if(from.isAfter(to)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>365)
            throw new BusinessException("Khoảng ngày không hợp lệ, tối đa 366 ngày tính cả ngày đầu và cuối.");
        return new Period(from,to);
    }
    public Dashboard dashboard(Actor a,Long branch,Period period) {
        ops.roles(a,Role.ADMIN);catalog.validateBranch(branch);
        var today=LocalDate.now(Reports.ZONE);var monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var recentFrom=today.minusDays(29).isBefore(today.withDayOfMonth(1))?today.minusDays(29):today.withDayOfMonth(1);
        if(monday.isBefore(recentFrom))recentFrom=monday;
        var recent=invoices.paid(branch,recentFrom.atStartOfDay(Reports.ZONE).toOffsetDateTime(),today.plusDays(1).atStartOfDay(Reports.ZONE).toOffsetDateTime());
        var paid=invoices.paid(branch,period.start(),period.end());
        var chartPaid=invoices.paid(branch,period.to().minusDays(29).atStartOfDay(Reports.ZONE).toOffsetDateTime(),period.end());
        var byBranch=new LinkedHashMap<Long,BranchRevenue>();
        branches.findAll().stream().filter(b->branch==null||b.getId().equals(branch)).forEach(b->byBranch.put(b.getId(),new BranchRevenue(b.getName(),BigDecimal.ZERO,0)));
        for(var invoice:paid){var b=invoice.getSession().getTable().getBranch();var prior=byBranch.get(b.getId());byBranch.put(b.getId(),new BranchRevenue(b.getName(),prior.revenue().add(invoice.getTotal()),prior.invoices()+1));}
        Specification<AuditLog> activity=(root,q,cb)->{var p=cb.and(cb.greaterThanOrEqualTo(root.get("createdAt"),period.start()),cb.lessThan(root.get("createdAt"),period.end()));return branch==null?p:cb.and(p,cb.equal(root.get("branchId"),branch));};
        long total=tables.activeCount(branch),open=sessions.openCount(branch);
        List<Long> ids=paid.stream().map(i->i.getSession().getId()).toList();
        return new Dashboard(sumSince(recent,today),sumSince(recent,monday),sumSince(recent,today.withDayOfMonth(1)),sum(paid),paid.size(),
            total,open,Math.max(0,total-open),open,users.activeStaff(branch,List.of(Role.ADMIN,Role.MANAGER,Role.WAITER,Role.CHEF)),dishes.sellingCount(branch),
            counts(branch,period),chart(chartPaid,period.to(),7),chart(chartPaid,period.to(),30),ids.isEmpty()?List.of():orders.findTopDishes(ids),
            List.copyOf(byBranch.values()),audit.findAll(activity,PageRequest.of(0,12,Sort.by("createdAt").descending().and(Sort.by("id").descending()))).getContent(),calls.pendingCount(branch),calls.pending(branch,PageRequest.of(0,20)));
    }
    public OrderSearch searchOrders(Actor a,String q,Long branch,OrderStatus status,Period period,int page,int size) {
        ops.roles(a,Role.ADMIN);catalog.validateBranch(branch);String text=AdminCatalog.search(q);
        Specification<FoodOrder> spec=(root,query,cb)->{
            var p=new ArrayList<Predicate>();p.add(cb.greaterThanOrEqualTo(root.get("createdAt"),period.start()));p.add(cb.lessThan(root.get("createdAt"),period.end()));
            if(branch!=null)p.add(cb.equal(root.get("session").get("table").get("branch").get("id"),branch));
            if(status!=null)p.add(cb.equal(root.get("status"),status));
            if(!text.isEmpty()) {
                var table=cb.like(cb.lower(root.get("session").get("table").get("label")),AdminCatalog.like(text),'\\');
                var name=cb.like(cb.lower(root.get("session").get("table").get("branch").get("name")),AdminCatalog.like(text),'\\');
                Predicate code=cb.disjunction();
                if(text.matches("(?:ut)?[0-9]{1,18}"))try{code=cb.equal(root.get("id"),Long.parseLong(text.replaceFirst("^ut","")));}catch(NumberFormatException ignored){}
                p.add(cb.or(table,name,code));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var result=orders.findAll(spec,AdminCatalog.paging(page,size,Sort.by("createdAt").descending().and(Sort.by("id").descending())));
        if(!result.isEmpty())orders.fetchItems(result.getContent().stream().map(FoodOrder::getId).toList());
        var sessionIds=result.getContent().stream().map(o->o.getSession().getId()).distinct().toList();
        Set<Long> paidSessions=new HashSet<>();if(!sessionIds.isEmpty())invoices.findBySessionIdIn(sessionIds).forEach(i->paidSessions.add(i.getSession().getId()));
        return new OrderSearch(result.map(o->row(o,paidSessions.contains(o.getSession().getId()))),counts(branch,period));
    }
    public OrderDetail detail(Actor a,Long id) {
        ops.roles(a,Role.ADMIN);var order=orders.findById(id).orElseThrow(()->new BusinessException("Không tìm thấy đơn."));
        var invoice=invoices.findBySessionId(order.getSession().getId()).orElse(null);
        var history=audit.findByDetailAndActionInOrderByCreatedAtAscIdAsc(order.getCode(),List.of("PLACE_ORDER","ORDER_CONFIRMED","ORDER_COOKING","ORDER_READY","ORDER_SERVED","ORDER_CANCELLED"));
        return new OrderDetail(row(order,invoice!=null),invoice==null?null:invoice.getId(),history);
    }
    private OrderRow row(FoodOrder o,boolean paid){return new OrderRow(ops.view(o),o.getSession().getTable().getBranch().getName(),o.getSession().isOpen(),paid);}
    private List<StatusTotal> counts(Long branch,Period period){Map<OrderStatus,Long> map=new EnumMap<>(OrderStatus.class);orders.statusCounts(branch,period.start(),period.end()).forEach(c->map.put(c.getStatus(),c.getCount()));return Arrays.stream(OrderStatus.values()).map(s->new StatusTotal(s,map.getOrDefault(s,0L))).toList();}
    private BigDecimal sum(List<Invoice> paid){return paid.stream().map(Invoice::getTotal).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private BigDecimal sumSince(List<Invoice> paid,LocalDate from){return sum(paid.stream().filter(i->!i.getPaidAt().atZoneSameInstant(Reports.ZONE).toLocalDate().isBefore(from)).toList());}
    private List<Reports.Day> chart(List<Invoice> paid,LocalDate end,int days) {
        var totals=new HashMap<LocalDate,BigDecimal>();paid.stream().filter(i->!i.getPaidAt().atZoneSameInstant(Reports.ZONE).toLocalDate().isBefore(end.minusDays(days-1))).forEach(i->totals.merge(i.getPaidAt().atZoneSameInstant(Reports.ZONE).toLocalDate(),i.getTotal(),BigDecimal::add));
        BigDecimal max=totals.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE).max(BigDecimal.ONE);
        var result=new ArrayList<Reports.Day>();for(int i=days-1;i>=0;i--){var date=end.minusDays(i);var value=totals.getOrDefault(date,BigDecimal.ZERO);result.add(new Reports.Day(date,value,value.multiply(BigDecimal.valueOf(100)).divide(max,0,RoundingMode.HALF_UP).intValue()));}return result;
    }
}
