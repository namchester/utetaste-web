package vn.utetaste.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.security.MemberCookieContext.QrContext;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class MemberService {
    private final AppUserRepository users;private final FoodOrderRepository orders;private final InvoiceRepository invoices;
    private final MemberFavoriteRepository favorites;private final MemberPointEntryRepository points;
    private final DishRepository dishes;private final BranchDishRepository availability;private final DiningSessionRepository sessions;
    private final OrderService ops;private final EntityManager em;
    public record Profile(Long id,String fullName,String email,boolean emailVerified,String phone,OffsetDateTime createdAt) {}
    public record ProfileForm(@NotBlank @Size(max=100) String fullName,@Size(max=24) String phone) {}
    public record FavoriteRow(Long id,String name,String description,String category,BigDecimal price,String imageUrl,boolean active,boolean atTable) {}
    public record MemberOrder(OrderService.OrderView order,String branch,boolean paid,String invoiceCode,OffsetDateTime paidAt,String method) {}
    public record Overview(Profile profile,long paidOrders,long favoriteCount,long balance,List<MemberOrder> recent,List<FavoriteRow> favorites) {}
    public record PointRow(int points,String reason,String reference,OffsetDateTime createdAt) {}
    public Profile profile(Actor a){return profile(user(a,false));}
    private Profile profile(AppUser u){return new Profile(u.getId(),u.getFullName(),u.getEmail(),u.isEmailVerified(),u.getPhone(),u.getCreatedAt());}
    private AppUser user(Actor a,boolean lock) {
        if(a==null||a.role()!=Role.USER)throw new AccessDeniedException("Chức năng dành cho khách hàng thành viên.");
        var u=(lock?users.lockById(a.id()):users.findById(a.id())).orElseThrow(()->new AccessDeniedException("Tài khoản không hợp lệ."));
        if(lock)em.refresh(u);validate(u);return u;
    }
    private AppUser user(MemberIdentity identity) {
        var u=users.findById(identity.id()).orElseThrow(()->new AccessDeniedException("Tài khoản không hợp lệ."));validate(u);
        if(u.getTokenVersion()!=identity.version())throw new AccessDeniedException("Phiên thành viên đã hết hiệu lực.");return u;
    }
    private void validate(AppUser u){if(u.getRole()!=Role.USER||!u.isActive()||!u.isEmailVerified()||u.getEmail()==null)throw new AccessDeniedException("Tài khoản thành viên chưa đủ điều kiện.");}
    @Transactional public void updateProfile(Actor a,ProfileForm form) {
        var u=user(a,true);String name=form.fullName()==null?"":form.fullName().strip();
        if(name.isEmpty()||name.length()>100)throw new BusinessException("Họ tên phải từ 1 đến 100 ký tự.");
        String phone=form.phone()==null?"":form.phone().strip();
        if(phone.length()>24)throw new BusinessException("Số điện thoại quá dài.");
        if(!phone.isEmpty()&&!phone.matches("[+0-9 ()-]+"))throw new BusinessException("Số điện thoại chỉ gồm chữ số và ký tự phân cách hợp lệ.");
        phone=phone.replaceAll("[ ()-]","");
        if(!phone.isEmpty()&&!phone.matches("\\+?[0-9]{8,15}"))throw new BusinessException("Số điện thoại cần 8 đến 15 chữ số, có thể bắt đầu bằng +.");
        u.setFullName(name);u.setPhone(phone.isEmpty()?null:phone);ops.log(a,"UPDATE_MEMBER_PROFILE","Cập nhật thông tin cá nhân",null);
    }
    public Overview overview(Actor a,QrContext table) {
        var u=user(a,false);var recent=orderPage(u.getId(),null,null,null,"newest",PageRequest.of(0,5)).getContent();
        var saved=favoritePage(u.getId(),table,PageRequest.of(0,4));
        return new Overview(profile(u),orders.paidMemberOrders(u.getId()),favorites.countByUserId(u.getId()),points.balance(u.getId()),recent,saved.getContent());
    }
    public Page<MemberOrder> history(Actor a,LocalDate from,LocalDate to,OrderStatus status,String sort,int page,int size) {
        var u=user(a,false);return orderPage(u.getId(),from,to,status,sort,AdminCatalog.paging(page,size,Sort.unsorted()));
    }
    private Page<MemberOrder> orderPage(Long user,LocalDate from,LocalDate to,OrderStatus status,String sort,Pageable pageable) {
        if(!Set.of("newest","oldest").contains(sort))throw new BusinessException("Cách sắp xếp không hợp lệ.");
        AdminQueries.Period period=(from==null&&to==null)?null:AdminQueries.period(from,to);
        Specification<FoodOrder> spec=(o,q,cb)->{
            var p=cb.equal(o.get("customer").get("id"),user);
            if(period!=null)p=cb.and(p,cb.greaterThanOrEqualTo(o.get("createdAt"),period.start()),cb.lessThan(o.get("createdAt"),period.end()));
            if(status!=null)p=cb.and(p,cb.equal(o.get("status"),status));return p;
        };
        Sort order=Sort.by("createdAt","id");if(sort.equals("newest"))order=order.descending();
        var data=orders.findAll(spec,PageRequest.of(pageable.getPageNumber(),pageable.getPageSize(),order));
        if(!data.isEmpty())orders.fetchItems(data.stream().map(FoodOrder::getId).toList());
        var ids=data.stream().map(o->o.getSession().getId()).distinct().toList();Map<Long,Invoice> paid=new HashMap<>();
        if(!ids.isEmpty())invoices.findBySessionIdIn(ids).forEach(i->paid.put(i.getSession().getId(),i));
        return data.map(o->view(o,paid.get(o.getSession().getId())));
    }
    public MemberOrder detail(Actor a,Long id) {
        var u=user(a,false);var order=orders.findByIdAndCustomerId(id,u.getId()).orElseThrow(()->new AccessDeniedException("Bạn không có quyền xem đơn này."));
        return view(order,invoices.findBySessionId(order.getSession().getId()).orElse(null));
    }
    private MemberOrder view(FoodOrder o,Invoice invoice) {
        // Do not expose any shared invoice amounts, cashier or other customers' lines.
        return new MemberOrder(ops.view(o),o.getSession().getTable().getBranch().getName(),invoice!=null,invoice==null?null:invoice.getCode(),invoice==null?null:invoice.getPaidAt(),invoice==null?null:invoice.getMethod().name());
    }
    public List<OrderService.OrderView> qrOrders(MemberIdentity member,Actor guest) {
        var u=user(member);var s=ops.guestSession(guest);
        return orders.findByCustomerIdAndSessionIdOrderByCreatedAtAscIdAsc(u.getId(),s.getId()).stream().map(ops::view).toList();
    }
    public Set<Long> favoriteIds(MemberIdentity identity,List<Long> ids){var u=user(identity);return ids.isEmpty()?Set.of():favorites.selected(u.getId(),ids);}
    @Transactional public void setFavorite(Actor a,Long dishId,boolean selected) {
        var u=user(a,true);var existing=favorites.findByUserIdAndDishId(u.getId(),dishId);
        if(!selected){existing.ifPresent(favorites::delete);return;}
        if(existing.isPresent())return;
        var dish=dishes.findById(dishId).orElseThrow(()->new BusinessException("Món không tồn tại."));
        if(!dish.isActive())throw new BusinessException("Món đã ngừng bán, chưa thể lưu mới.");
        var f=new MemberFavorite();f.setUser(u);f.setDish(dish);favorites.saveAndFlush(f);
    }
    public Page<FavoriteRow> favorites(Actor a,QrContext table,int page,int size){return favoritePage(user(a,false).getId(),table,AdminCatalog.paging(page,size,Sort.unsorted()));}
    private Page<FavoriteRow> favoritePage(Long user,QrContext table,Pageable pageable) {
        var data=favorites.findByUserIdOrderByCreatedAtDescIdDesc(user,pageable);var ids=data.stream().map(f->f.getDish().getId()).toList();
        Set<Long> available=(table==null||ids.isEmpty())?Set.of():availability.availableIds(table.branchId(),ids);
        return data.map(f->favorite(f.getDish(),available.contains(f.getDish().getId())));
    }
    private FavoriteRow favorite(Dish d,boolean atTable){return new FavoriteRow(d.getId(),d.getName(),d.getDescription(),d.getCategory().getName(),d.getPrice(),d.getImageUrl(),d.isActive(),atTable);}
    public FavoriteRow favoriteDetail(Actor a,Long dishId,QrContext table) {
        var u=user(a,false);var f=favorites.findByUserIdAndDishId(u.getId(),dishId).orElseThrow(()->new BusinessException("Món chưa nằm trong danh sách yêu thích của bạn."));
        boolean available=table!=null&&availability.availableIds(table.branchId(),List.of(dishId)).contains(dishId);return favorite(f.getDish(),available);
    }
    public Long choose(Actor a,Long dishId,QrContext table) {
        var f=favoriteDetail(a,dishId,table);
        if(table==null)throw new BusinessException("Hãy quét QR của bàn đang mở để chọn món.");
        var s=sessions.findById(table.sessionId()).orElseThrow(()->new BusinessException("Phiên bàn không còn tồn tại."));
        if(!s.isOpen()||!s.getTable().isActive()||!s.getTable().getBranch().isActive()||!s.getTable().getBranch().getId().equals(table.branchId())||!f.atTable())
            throw new BusinessException("Món hoặc phiên bàn không còn khả dụng. Hãy quét QR và xem lại thực đơn.");
        return dishId;
    }
    public long balance(Actor a){return points.balance(user(a,false).getId());}
    public Page<PointRow> points(Actor a,int page,int size){return points.findByUserIdOrderByCreatedAtDescIdDesc(user(a,false).getId(),AdminCatalog.paging(page,size,Sort.unsorted())).map(e->new PointRow(e.getPoints(),e.getReason(),e.getReferenceKey(),e.getCreatedAt()));}
}
