package vn.utetaste.service;
import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.constraints.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
@Service @RequiredArgsConstructor @Transactional
public class AdminService {
    private final BranchRepository branches; private final CategoryRepository categories; private final DishRepository dishes;
    private final BranchDishRepository availability; private final RestaurantTableRepository tables;
    private final DiningSessionRepository sessions; private final AppUserRepository users; private final PasswordEncoder encoder; private final OrderService ops;
    private final EntityManager em;
    public record BranchForm(Long id,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=255) String address,@NotBlank @Size(max=20) String phone,boolean active) {}
    public record DishForm(Long id,@NotBlank @Size(max=120) String name,@NotNull @Positive @DecimalMax("10000000") BigDecimal price,@NotBlank @Size(max=1000) String description,@NotNull Long categoryId,boolean active) {}
    public record TableForm(Long id,@NotNull Long branchId,@NotBlank @Size(max=30) String label,@Min(1) @Max(30) int capacity,boolean active) {}
    public record UserForm(Long id,@NotBlank @Pattern(regexp="[a-zA-Z0-9_.-]{3,60}") String username,@NotBlank @Size(max=100) String fullName,@NotNull Role role,Long branchId,@Size(max=72) String password,boolean active) {}
    public Branch branch(Actor a,BranchForm f) {
        ops.roles(a,Role.ADMIN);var b=f.id()==null?new Branch():branches.findById(f.id()).orElseThrow();
        if(!f.active()&&f.id()!=null&&sessions.findByClosedAtIsNull().stream().anyMatch(s->s.getTable().getBranch().getId().equals(f.id()))) throw new BusinessException("Hãy đóng hoặc thanh toán các bàn trước khi ngừng chi nhánh.");
        b.setName(f.name().strip());b.setAddress(f.address().strip());b.setPhone(f.phone().strip());b.setActive(f.active());branches.save(b);
        for(var d:dishes.findAll()) if(availability.findByBranchIdAndDishId(b.getId(),d.getId()).isEmpty()) {var bd=new BranchDish();bd.setBranch(b);bd.setDish(d);availability.save(bd);}
        ops.log(a,"SAVE_BRANCH",b.getName(),b.getId());return b;
    }
    public void category(Actor a,Long id,String name,int sortOrder) {
        ops.roles(a,Role.ADMIN);if(name==null||name.isBlank()||name.length()>80) throw new BusinessException("Tên danh mục phải từ 1 đến 80 ký tự.");
        if(sortOrder<0||sortOrder>10000) throw new BusinessException("Thứ tự danh mục phải từ 0 đến 10000.");
        var c=id==null?new Category():categories.lockById(id).orElseThrow();c.setName(name.strip());c.setSortOrder(sortOrder);categories.save(c);ops.log(a,"SAVE_CATEGORY",c.getName(),null);
    }
    public void deleteCategory(Actor a,Long id) {
        ops.roles(a,Role.ADMIN);
        var category=categories.lockById(id).orElseThrow(()->new BusinessException("Danh mục không tồn tại hoặc đã được xóa."));
        long count=dishes.countByCategoryId(id);
        if(count>0) throw new BusinessException("Danh mục còn "+count+" món (kể cả món ngừng bán). Hãy chuyển các món sang danh mục khác trước khi xóa.");
        String detail="Danh mục #"+id+" • "+category.getName();
        categories.delete(category);categories.flush();ops.log(a,"DELETE_CATEGORY",detail,null);
    }
    public void dishStates(Actor a,List<Long> ids,boolean active) {
        ops.roles(a,Role.ADMIN);
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(Objects::isNull))
            throw new BusinessException("Chọn từ 1 đến 100 món để cập nhật.");
        var selected=ids.stream().distinct().sorted().map(id->dishes.lockById(id)
            .orElseThrow(()->new BusinessException("Một món đã chọn không còn tồn tại. Chưa cập nhật món nào."))).toList();
        for(var dish:selected) {dish.setActive(active);ops.log(a,active?"ENABLE_DISH":"DISABLE_DISH","Món #"+dish.getId()+" • "+dish.getName(),null);}
    }
    public Dish dish(Actor a,DishForm f) {
        ops.roles(a,Role.ADMIN);if(f.price().stripTrailingZeros().scale()>0) throw new BusinessException("Giá VND phải là số nguyên.");
        var category=categories.lockById(f.categoryId()).orElseThrow(()->new BusinessException("Danh mục không còn tồn tại."));
        var d=f.id()==null?new Dish():dishes.lockById(f.id()).orElseThrow();d.setName(f.name().strip());d.setDescription(f.description().strip());d.setPrice(f.price());d.setActive(f.active());d.setCategory(category);dishes.save(d);
        for(var b:branches.findAll()) if(availability.findByBranchIdAndDishId(b.getId(),d.getId()).isEmpty()) {var bd=new BranchDish();bd.setBranch(b);bd.setDish(d);availability.save(bd);}
        ops.log(a,"SAVE_DISH",d.getName(),null);return d;
    }
    public void table(Actor a,TableForm f) {
        ops.roles(a,Role.ADMIN,Role.MANAGER);ops.checkBranch(a,f.branchId());
        var t=f.id()==null?new RestaurantTable():tables.lockById(f.id()).orElseThrow();
        if(f.id()!=null) {
            ops.checkBranch(a,t.getBranch().getId());
            if(!t.getBranch().getId().equals(f.branchId())) throw new BusinessException("Không chuyển chi nhánh của bàn đã tạo.");
            if(!f.active()&&sessions.findByTableIdAndClosedAtIsNull(t.getId()).isPresent()) throw new BusinessException("Bàn đang có khách, chưa thể ngừng sử dụng.");
        }
        t.setBranch(branches.findById(f.branchId()).orElseThrow());t.setLabel(f.label().strip());t.setCapacity(f.capacity());t.setActive(f.active());tables.save(t);ops.log(a,"SAVE_TABLE",t.getLabel(),f.branchId());
    }
    public void rotateQr(Actor a,Long id) {
        ops.roles(a,Role.ADMIN,Role.MANAGER);var t=tables.lockById(id).orElseThrow();ops.checkBranch(a,t.getBranch().getId());
        if(sessions.findByTableIdAndClosedAtIsNull(id).isPresent()) throw new BusinessException("Chỉ đổi QR khi bàn không có khách.");
        t.setQrKey(UUID.randomUUID().toString());ops.log(a,"ROTATE_QR",t.getLabel(),t.getBranch().getId());
    }
    public void availability(Actor a,Long branchId,Long dishId,boolean available) {
        ops.roles(a,Role.ADMIN,Role.MANAGER);ops.checkBranch(a,branchId);
        var bd=availability.findByBranchIdAndDishId(branchId,dishId).orElseThrow();bd.setAvailable(available);ops.log(a,"DISH_AVAILABILITY",bd.getDish().getName()+"="+available,branchId);
    }
    public void user(Actor a,UserForm f) {
        ops.roles(a,Role.ADMIN);if(f.role()==Role.GUEST||f.role()==Role.USER) throw new BusinessException("Tài khoản cá nhân được tạo qua đăng ký email.");
        guardStaffMutation(a);
        var u=f.id()==null?new AppUser():lockUser(f.id());
        if(f.id()!=null&&(u.getRole()==Role.USER||u.getRole()==Role.GUEST)) throw new BusinessException("Không đổi tài khoản cá nhân thành tài khoản nhân viên.");
        if(Objects.equals(a.id(),f.id())&&(!f.active()||f.role()!=Role.ADMIN)) throw new BusinessException("Không tự khóa hoặc hạ quyền tài khoản hiện tại.");
        protectLastAdmin(u,f.active(),f.role());
        var branch=f.role()==Role.ADMIN?null:branches.findById(Objects.requireNonNullElse(f.branchId(),-1L)).orElseThrow(()->new BusinessException("Nhân viên phải có chi nhánh."));
        if(branch!=null&&!branch.isActive()&&(f.id()==null||u.getBranch()==null||!branch.getId().equals(u.getBranch().getId()))) throw new BusinessException("Chọn chi nhánh đang hoạt động để phân công mới.");
        if(f.password()!=null&&!f.password().isBlank()) { validatePassword(f.password());u.setPasswordHash(encoder.encode(f.password())); }
        else if(f.id()==null) throw new BusinessException("Cần đặt mật khẩu cho tài khoản mới.");
        u.setUsername(f.username());u.setFullName(f.fullName().strip());u.setRole(f.role());u.setBranch(branch);u.setActive(f.active());u.setTokenVersion(u.getTokenVersion()+1);users.save(u);ops.log(a,"SAVE_USER",u.getUsername(),branch==null?null:branch.getId());
    }
    public void userState(Actor a,Long id,boolean active) {
        ops.roles(a,Role.ADMIN);guardStaffMutation(a);var u=lockUser(id);
        if(u.getRole()==Role.USER||u.getRole()==Role.GUEST) throw new BusinessException("Tài khoản không thuộc quản trị nhân sự.");
        if(Objects.equals(a.id(),id)&&!active) throw new BusinessException("Không tự khóa tài khoản hiện tại.");
        protectLastAdmin(u,active,u.getRole());
        if(u.isActive()==active) return;
        u.setActive(active);u.setTokenVersion(u.getTokenVersion()+1);
        ops.log(a,active?"ENABLE_USER":"DISABLE_USER",u.getUsername(),u.getBranch()==null?null:u.getBranch().getId());
    }
    private void guardStaffMutation(Actor a) {
        // Every staff mutation acquires the ADMIN rows in one order before its target.
        // Refresh after waiting: the request principal may have been demoted concurrently.
        var admins=users.lockAdmins();admins.forEach(em::refresh);
        if(admins.stream().noneMatch(u->u.getId().equals(a.id())&&u.isActive()&&u.getRole()==Role.ADMIN))
            throw new org.springframework.security.access.AccessDeniedException("Quyền quản trị đã thay đổi. Hãy đăng nhập lại.");
    }
    private void protectLastAdmin(AppUser u,boolean active,Role role) {
        if(u.getRole()==Role.ADMIN&&u.isActive()&&(!active||role!=Role.ADMIN)&&users.countByRoleAndActiveTrue(Role.ADMIN)<=1)
            throw new BusinessException("Phải giữ ít nhất một quản trị viên đang hoạt động.");
    }
    public void password(Actor a,String oldPassword,String newPassword) {
        ops.roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER,Role.CHEF,Role.USER);
        if(oldPassword==null||oldPassword.isEmpty()||oldPassword.length()>72
            ||oldPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) {
            throw new BusinessException("Mật khẩu hiện tại chưa đúng.");
        }
        var u=lockUser(a.id());if(!encoder.matches(oldPassword,u.getPasswordHash())) throw new BusinessException("Mật khẩu hiện tại chưa đúng.");
        validatePassword(newPassword);u.setPasswordHash(encoder.encode(newPassword));u.setTokenVersion(u.getTokenVersion()+1);ops.log(a,"CHANGE_PASSWORD",u.getUsername(),a.branchId());
    }
    public void logout(Actor a) {
        if(a!=null&&a.id()!=null) {
            var u=lockUser(a.id());
            u.setTokenVersion(u.getTokenVersion()+1);
        }
    }
    private AppUser lockUser(Long id) {
        var u=users.lockById(id).orElseThrow(()->new BusinessException("Tài khoản không còn tồn tại."));
        // A request may already have loaded this account before waiting for the lock.
        em.refresh(u);
        return u;
    }
    public static void validatePassword(String p) {if(p==null||p.length()<10||p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72) throw new BusinessException("Mật khẩu cần ít nhất 10 ký tự và không quá 72 byte.");}
}
