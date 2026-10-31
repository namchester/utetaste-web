package vn.utetaste.service;

import java.util.*;
import java.util.function.Supplier;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.utetaste.domain.Role;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;

/** No outer transaction: a rejected item cannot roll back another successful deletion. */
@Service @RequiredArgsConstructor
public class AdminRemovalService {
    private final PlatformTransactionManager transactions;
    private final AdminService admin;
    private final OrderService ops;
    private final BranchRepository branches;
    private final DishRepository dishes;
    private final BranchDishRepository menu;
    private final AppUserRepository users;
    private final RestaurantTableRepository tables;
    private final OrderItemRepository items;
    private final MemberFavoriteRepository favorites;
    private final AuditLogRepository audit;
    private final EntityManager em;

    public record Item(Long id,String name,boolean removed,String reason) {}
    public record Result(List<Item> items) {
        public long removedCount() { return items.stream().filter(Item::removed).count(); }
        public String message() { return "Đã xóa "+removedCount()+" món; giữ lại "+(items.size()-removedCount())+" món. Xem lý do bên dưới."; }
    }
    private <T> T transaction(Supplier<T> work) {
        var tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status->work.get());
    }
    public void deleteBranch(Actor actor,Long id) {
        ops.roles(actor,Role.ADMIN);
        if(id==null||id<=0) throw new BusinessException("Mã chi nhánh không hợp lệ.");
        transaction(()->{
            admin.lockAdminForManagement(actor);
            var branch=branches.lockById(id).orElseThrow(()->ManagementException.missing("Chi nhánh không tồn tại hoặc đã được xóa."));
            em.refresh(branch);
            if(users.existsByBranchId(id)||tables.existsByBranchId(id)
                ||audit.existsByBranchIdAndActionNotIn(id,List.of("SAVE_BRANCH","DISH_AVAILABILITY","BRANCH_STATE")))
                throw ManagementException.conflict("Chi nhánh có nhân viên, bàn hoặc lịch sử hoạt động. Không xóa dữ liệu phụ thuộc; hãy dùng Ngừng hoạt động sau khi đóng các bàn.");
            // Only setup links are removable. Business records and audit history are never deleted.
            menu.removeForBranch(id);
            String detail="Chi nhánh #"+id+" • "+branch.getName();
            branches.delete(branch);branches.flush();
            ops.log(actor,"DELETE_BRANCH",detail,id);
            return null;
        });
    }
    public Result deleteDishes(Actor actor,List<Long> ids) {
        ops.roles(actor,Role.ADMIN);
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(id->id==null||id<=0))
            throw new BusinessException("Chọn từ 1 đến 100 mã món hợp lệ để xóa.");
        List<Item> result=new ArrayList<>();
        for(Long id:ids.stream().distinct().sorted().toList()) {
            try {
                result.add(transaction(()->{
                    admin.lockAdminForManagement(actor);
                    var dish=dishes.lockById(id).orElseThrow(()->ManagementException.missing("Món không tồn tại hoặc đã được xóa."));
                    em.refresh(dish);
                    if(items.existsByDishId(id))
                        return new Item(id,dish.getName(),false,"Món có lịch sử đơn hàng hoặc đơn đang xử lý. Hãy dùng Ngừng bán.");
                    if(favorites.existsByDishId(id))
                        return new Item(id,dish.getName(),false,"Món có trong Favorites của khách hàng. Hãy dùng Ngừng bán.");
                    String name=dish.getName();
                    menu.removeForDish(id);
                    dishes.delete(dish);dishes.flush();
                    ops.log(actor,"DELETE_DISH","Món #"+id+" • "+name,null);
                    // Cloudinary resources may be shared; deleting a row never deletes the remote image.
                    return new Item(id,name,true,"Đã xóa món chưa phát sinh dữ liệu nghiệp vụ; giữ nguyên tài nguyên ảnh.");
                }));
            } catch(BusinessException e) {
                result.add(new Item(id,"Món #"+id,false,e.getMessage()));
            } catch(DataIntegrityViolationException|ConcurrencyFailureException e) {
                // The item transaction has already rolled back, including setup links and audit.
                result.add(new Item(id,"Món #"+id,false,"Dữ liệu vừa thay đổi hoặc món đang được sử dụng. Hãy tải lại; có thể dùng Ngừng bán."));
            }
        }
        return new Result(List.copyOf(result));
    }
}
