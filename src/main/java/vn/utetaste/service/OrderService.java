package vn.utetaste.service;
import java.math.*;
import java.time.*;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
import vn.utetaste.security.MemberIdentity;

@Service @RequiredArgsConstructor @Transactional
public class OrderService {
    private final BranchRepository branches;
    private final RestaurantTableRepository tables;
    private final DiningSessionRepository sessions;
    private final FoodOrderRepository orders;
    private final BranchDishRepository branchDishes;
    private final InvoiceRepository invoices;
    private final ServiceCallRepository calls;
    private final AuditLogRepository audit;
    private final ApplicationEventPublisher events;
    private final EntityManager em;
    private final AppUserRepository users;
    public record Line(@NotNull Long dishId,@Min(1) @Max(20) int quantity) {}
    public record PlaceRequest(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String requestKey,
        @NotEmpty @Size(max=30) List<@NotNull @Valid Line> items, @Size(max=500) String note) {}
    public record ItemView(String name,int quantity,BigDecimal price,BigDecimal total) {}
    public record OrderView(Long id,String code,Long sessionId,String tableLabel,OrderStatus status,String note,OffsetDateTime createdAt,BigDecimal total,List<ItemView> items) {}
    public record TableView(RestaurantTable table,DiningSession session,BigDecimal total,int orderCount) {}
    public Long branchId(Actor a,Long requested) {
        if(a.isGuest()) throw new AccessDeniedException("Không có quyền quản lý.");
        Long selected=requested;
        if(selected==null) selected=a.isAdmin()?branches.findByActiveTrueOrderByName().stream().findFirst().map(Branch::getId).orElse(null):a.branchId();
        checkBranch(a,selected);
        if(selected==null||!branches.existsById(selected)) throw new BusinessException("Chưa có chi nhánh. Quản trị viên cần tạo chi nhánh trước.");
        return selected;
    }
    public void checkBranch(Actor a,Long branchId) {
        if(!a.isAdmin()&&!Objects.equals(a.branchId(),branchId)) throw new AccessDeniedException("Bạn không có quyền tại chi nhánh này.");
    }
    public void roles(Actor a,Role... allowed) {
        if(Arrays.stream(allowed).noneMatch(r->r==a.role())) throw new AccessDeniedException("Vai trò không được phép thực hiện thao tác.");
    }
    public DiningSession openTable(Actor a,Long tableId) {
        roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER);
        var t=tables.lockById(tableId).orElseThrow(()->new BusinessException("Không tìm thấy bàn."));
        checkBranch(a,t.getBranch().getId());
        if(!t.isActive()||!t.getBranch().isActive()) throw new BusinessException("Bàn hoặc chi nhánh đang ngừng hoạt động.");
        var existing=sessions.findByTableIdAndClosedAtIsNull(tableId);
        if(existing.isPresent()) return existing.get();
        var s=new DiningSession();s.setTable(t);sessions.save(s);
        log(a,"OPEN_TABLE",t.getLabel(),t.getBranch().getId());emit(s,"tables.changed");return s;
    }
    public DiningSession join(String key) {
        var t=tables.findByQrKey(key).orElseThrow(()->new BusinessException("Mã QR không còn hợp lệ."));
        if(!t.isActive()||!t.getBranch().isActive()) throw new BusinessException("Bàn đang ngừng phục vụ.");
        return sessions.findByTableIdAndClosedAtIsNull(t.getId()).orElseThrow(()->new BusinessException("Bàn chưa được mở. Vui lòng nhờ nhân viên mở bàn trước khi gọi món."));
    }
    public DiningSession guestSession(Actor a) {
        if(!a.isGuest()) throw new AccessDeniedException("Cần quét QR của bàn.");
        return sessions.findById(a.sessionId()).orElseThrow(()->new BusinessException("Phiên bàn không tồn tại."));
    }
    public OrderView place(Actor a,PlaceRequest request) {
        return place(a,request,null);
    }
    public OrderView place(Actor a,PlaceRequest request,MemberIdentity member) {
        if(!a.isGuest()) throw new AccessDeniedException("Cần quét QR của bàn.");
        var s=lockSession(a.sessionId());
        ensureOpen(s);
        AppUser customer=null;
        if(member!=null) {
            customer=users.lockById(member.id()).orElseThrow(()->new AccessDeniedException("Tài khoản thành viên không hợp lệ."));em.refresh(customer);
            if(customer.getRole()!=Role.USER||!customer.isActive()||!customer.isEmailVerified()||customer.getEmail()==null||customer.getTokenVersion()!=member.version())
                throw new AccessDeniedException("Phiên thành viên đã thay đổi. Hãy đăng nhập lại trước khi gửi món.");
        }
        var repeated=orders.findBySessionIdAndRequestKey(s.getId(),request.requestKey());
        if(repeated.isPresent()) {
            Long owner=repeated.get().getCustomer()==null?null:repeated.get().getCustomer().getId();
            if(!Objects.equals(owner,customer==null?null:customer.getId())) throw new BusinessException("Lượt gửi đã được xử lý dưới định danh khác. Không liên kết lại đơn cũ.");
            return view(repeated.get());
        }
        if(request.items()==null||request.items().isEmpty()||request.items().size()>30) throw new BusinessException("Chọn từ 1 đến 30 món.");
        var o=new FoodOrder();o.setSession(s);o.setCustomer(customer);o.setRequestKey(request.requestKey());o.setNote(request.note()==null?"":request.note().strip());
        Set<Long> seen=new HashSet<>();
        for(var line:request.items()) {
            if(line==null||line.quantity()<1||line.quantity()>20||!seen.add(line.dishId())) throw new BusinessException("Số lượng không hợp lệ hoặc món bị lặp.");
            var bd=branchDishes.findByBranchIdAndDishId(s.getTable().getBranch().getId(),line.dishId()).orElseThrow(()->new BusinessException("Món không thuộc thực đơn chi nhánh."));
            var d=bd.getDish();if(!d.isActive()||!bd.isAvailable()) throw new BusinessException("Món "+d.getName()+" đã tạm hết. Vui lòng cập nhật giỏ món.");
            var item=new OrderItem();item.setOrder(o);item.setDish(d);item.setDishName(d.getName());item.setUnitPrice(d.getPrice());item.setQuantity(line.quantity());o.getItems().add(item);
        }
        orders.saveAndFlush(o);log(a,"PLACE_ORDER",o.getCode(),s.getTable().getBranch().getId());emit(s,"orders.changed");return view(o);
    }
    public OrderView changeStatus(Actor a,Long orderId,OrderStatus next) {
        roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER,Role.CHEF);
        var o=orders.findById(orderId).orElseThrow(()->new BusinessException("Không tìm thấy đơn."));
        var s=lockSession(o.getSession().getId());
        checkBranch(a,s.getTable().getBranch().getId());ensureOpen(s);em.refresh(o);
        var old=o.getStatus();
        boolean valid=switch(old) {
            case NEW -> next==OrderStatus.CONFIRMED||next==OrderStatus.CANCELLED;
            case CONFIRMED -> next==OrderStatus.COOKING||next==OrderStatus.CANCELLED;
            case COOKING -> next==OrderStatus.READY;
            case READY -> next==OrderStatus.SERVED;
            default -> false;
        };
        if(!valid) throw new BusinessException("Không thể chuyển trạng thái từ "+old+" sang "+next+". Hãy tải lại danh sách.");
        if(a.role()==Role.CHEF && !(next==OrderStatus.COOKING||next==OrderStatus.READY)) throw new AccessDeniedException("Bếp chỉ được bắt đầu và hoàn tất chế biến.");
        if(a.role()==Role.WAITER && (next==OrderStatus.COOKING||next==OrderStatus.READY)) throw new AccessDeniedException("Trạng thái chế biến do bếp cập nhật.");
        o.setStatus(next);o.setUpdatedAt(OffsetDateTime.now());log(a,"ORDER_"+next,o.getCode(),s.getTable().getBranch().getId());emit(s,"orders.changed");return view(o);
    }
    public Invoice checkout(Actor a,Long sessionId,int discountPercent,PaymentMethod method) {
        roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER);
        var s=lockSession(sessionId);
        checkBranch(a,s.getTable().getBranch().getId());
        var prior=invoices.findBySessionId(sessionId);if(prior.isPresent()) return prior.get();
        ensureOpen(s);
        if(discountPercent<0||discountPercent>30) throw new BusinessException("Giảm giá phải từ 0 đến 30%.");
        if(a.role()==Role.WAITER && discountPercent!=0) throw new AccessDeniedException("Chỉ quản lý được áp dụng giảm giá.");
        var active=orders.findBySessionIdOrderByCreatedAt(sessionId).stream().filter(o->o.getStatus()!=OrderStatus.CANCELLED).toList();
        if(active.isEmpty()) throw new BusinessException("Bàn chưa có món để thanh toán.");
        if(active.stream().anyMatch(o->o.getStatus()!=OrderStatus.SERVED)) throw new BusinessException("Chỉ thanh toán khi tất cả món đã phục vụ. Hãy kiểm tra trạng thái đơn.");
        var subtotal=active.stream().map(FoodOrder::getTotal).reduce(BigDecimal.ZERO,BigDecimal::add);
        var invoice=new Invoice();invoice.setSession(s);invoice.setSubtotal(subtotal);
        invoice.setDiscountAmount(subtotal.multiply(BigDecimal.valueOf(discountPercent)).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP));
        invoice.setTotal(subtotal.subtract(invoice.getDiscountAmount()));invoice.setMethod(Objects.requireNonNull(method));invoice.setCashier(a.displayName());invoices.save(invoice);
        s.setClosedAt(OffsetDateTime.now());resolveCalls(s);log(a,"CHECKOUT",invoice.getCode()+" • "+invoice.getTotal(),s.getTable().getBranch().getId());emit(s,"session.closed");return invoice;
    }
    public void closeEmpty(Actor a,Long sessionId) {
        roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER);
        var s=lockSession(sessionId);checkBranch(a,s.getTable().getBranch().getId());ensureOpen(s);
        if(orders.findBySessionIdOrderByCreatedAt(sessionId).stream().anyMatch(o->o.getStatus()!=OrderStatus.CANCELLED)) throw new BusinessException("Bàn có món chưa thanh toán, không thể đóng trống.");
        s.setClosedAt(OffsetDateTime.now());resolveCalls(s);log(a,"CLOSE_EMPTY",s.getTable().getLabel(),s.getTable().getBranch().getId());emit(s,"session.closed");
    }
    public void call(Actor a,String message) {
        var s=lockSession(guestSession(a).getId());ensureOpen(s);
        if(message==null||message.isBlank()||message.length()>120) throw new BusinessException("Nội dung hỗ trợ phải từ 1 đến 120 ký tự.");
        if(calls.findFirstBySessionIdAndResolvedAtIsNull(s.getId()).isPresent()) return;
        var c=new ServiceCall();c.setSession(s);c.setMessage(message.strip());calls.save(c);emit(s,"calls.changed");
    }
    public void resolveCall(Actor a,Long id) {
        roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER);var c=calls.findById(id).orElseThrow();checkBranch(a,c.getSession().getTable().getBranch().getId());c.setResolvedAt(OffsetDateTime.now());emit(c.getSession(),"calls.changed");
    }
    private void resolveCalls(DiningSession s) { calls.findBySessionTableBranchIdAndResolvedAtIsNullOrderByCreatedAt(s.getTable().getBranch().getId()).stream().filter(c->c.getSession().getId().equals(s.getId())).forEach(c->c.setResolvedAt(OffsetDateTime.now())); }
    public List<OrderView> guestOrders(Actor a) { return orders.findBySessionIdAndCustomerIsNullOrderByCreatedAtAscIdAsc(guestSession(a).getId()).stream().map(this::view).toList(); }
    public List<OrderView> activeOrders(Actor a,Long branchId) { checkBranch(a,branchId);return orders.findBySessionTableBranchIdAndSessionClosedAtIsNullOrderByCreatedAtAsc(branchId).stream().filter(o->o.getStatus()!=OrderStatus.CANCELLED).map(this::view).toList(); }
    public List<TableView> tableViews(Actor a,Long branchId) {
        checkBranch(a,branchId);
        var open=new HashMap<Long,DiningSession>();sessions.findByTableBranchIdAndClosedAtIsNull(branchId).forEach(s->open.put(s.getTable().getId(),s));
        var totals=new HashMap<Long,FoodOrderRepository.SessionTotal>();orders.openSessionTotals(branchId).forEach(t->totals.put(t.getSessionId(),t));
        return tables.findByBranchIdOrderByLabel(branchId).stream().map(t->{
            var s=open.get(t.getId());var total=s==null?null:totals.get(s.getId());
            return new TableView(t,s,total==null?BigDecimal.ZERO:total.getTotal(),total==null?0:Math.toIntExact(total.getOrderCount()));
        }).toList();
    }
    private DiningSession lockSession(Long id) {
        var s=sessions.lockById(id).orElseThrow(()->new BusinessException("Phiên bàn không tồn tại."));
        // Authentication may already have loaded this session before checkout took the lock.
        em.refresh(s);return s;
    }
    public List<OrderView> sessionOrders(Actor a,Long id) {var s=sessions.findById(id).orElseThrow();checkBranch(a,s.getTable().getBranch().getId());return orders.findBySessionIdOrderByCreatedAt(id).stream().map(this::view).toList();}
    private void ensureOpen(DiningSession s) { if(!s.isOpen()||!s.getTable().isActive()||!s.getTable().getBranch().isActive()) throw new BusinessException("Phiên bàn đã kết thúc hoặc tạm ngừng phục vụ. Vui lòng quét QR lại."); }
    public OrderView view(FoodOrder o) { return new OrderView(o.getId(),o.getCode(),o.getSession().getId(),o.getSession().getTable().getLabel(),o.getStatus(),o.getNote(),o.getCreatedAt(),o.getTotal(),o.getItems().stream().map(i->new ItemView(i.getDishName(),i.getQuantity(),i.getUnitPrice(),i.getLineTotal())).toList()); }
    public void log(Actor a,String action,String detail,Long branchId) {var l=new AuditLog();l.setActor(a.username());l.setAction(action);l.setDetail(detail);l.setBranchId(branchId);audit.save(l);}
    public void emit(DiningSession s,String type) {events.publishEvent(new LiveEvent(s.getTable().getBranch().getId(),s.getId(),type));}
}
