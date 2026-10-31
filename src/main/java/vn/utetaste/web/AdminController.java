package vn.utetaste.web;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
import vn.utetaste.service.*;
@Controller @RequiredArgsConstructor
public class AdminController {
    private final AdminService admin;private final CloudinaryService cloud;
    private final AdminCatalog catalog;
    private final AdminRemovalService removal;
    private final AdminStaff staff;
    private final BranchRepository branches;private final CategoryRepository categories;private final DishRepository dishes;private final AppUserRepository users;private final AuditLogRepository audit;
    @GetMapping("/admin/branches") String branches(@RequestParam(required=false) Long edit,Model m) {
        m.addAttribute("branches",branches.findAll());
        m.addAttribute("editing",edit==null?null:branches.findById(edit).orElseThrow(()->ManagementException.missing("Chi nhánh không còn tồn tại.")));
        return "admin-branches";
    }
    @PostMapping("/admin/branches") String branch(@AuthenticationPrincipal Actor a,@Valid @ModelAttribute AdminService.BranchForm f,
            org.springframework.validation.BindingResult validation,Model m,jakarta.servlet.http.HttpServletResponse response,RedirectAttributes r) {
        try {
            if(validation.hasErrors()) throw new BusinessException("Kiểm tra các trường bắt buộc và độ dài tên, địa chỉ, điện thoại.");
            admin.branch(a,f);r.addFlashAttribute("success",f.id()==null?"Đã lưu chi nhánh.":"Đã lưu thay đổi chi nhánh.");return "redirect:/admin/branches";
        } catch(BusinessException e) {
            response.setStatus(managementStatus(e));m.addAttribute("branches",branches.findAll());m.addAttribute("editing",f);m.addAttribute("error",e.getMessage());return "admin-branches";
        }
    }
    @PostMapping("/admin/branches/{id}/state") String branchState(@AuthenticationPrincipal Actor a,@PathVariable Long id,@RequestParam boolean active,RedirectAttributes r) {
        try {admin.branchState(a,id,active);r.addFlashAttribute("success",active?"Đã kích hoạt lại chi nhánh.":"Đã ngừng hoạt động chi nhánh.");}
        catch(BusinessException e) {r.addFlashAttribute("error",e.getMessage());}
        return "redirect:/admin/branches";
    }
    @PostMapping("/admin/branches/{id}/delete") String deleteBranch(@AuthenticationPrincipal Actor a,@PathVariable Long id,RedirectAttributes r) {
        try {removal.deleteBranch(a,id);r.addFlashAttribute("success","Đã xóa chi nhánh trống; giữ nguyên món ăn và nhật ký.");}
        catch(BusinessException e) {r.addFlashAttribute("error",e.getMessage());}
        catch(org.springframework.dao.DataIntegrityViolationException|org.springframework.dao.ConcurrencyFailureException e) {
            r.addFlashAttribute("error","Chi nhánh đang được sử dụng hoặc dữ liệu vừa thay đổi. Không xóa dữ liệu phụ thuộc; hãy tải lại và cân nhắc Ngừng hoạt động.");
        }
        return "redirect:/admin/branches";
    }
    private int managementStatus(BusinessException e) {
        return e instanceof ManagementException me?(me.kind()==ManagementException.Kind.NOT_FOUND?404:409):400;
    }
    @GetMapping("/admin/menu") String menu(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long edit,
            @RequestParam(defaultValue="") String q,@RequestParam(required=false) Long categoryId,
            @RequestParam(defaultValue="all") String state,@RequestParam(required=false) Long branchId,
            @RequestParam(defaultValue="all") String available,@RequestParam(defaultValue="name") String sort,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(defaultValue="") String categoryQuery,Model m){
        var result=catalog.menu(a,q,categoryId,state,branchId,available,sort,page,size);
        m.addAttribute("dishes",result.getContent());m.addAttribute("dishPage",result);
        m.addAttribute("categories",categories.findAllByOrderBySortOrderAscNameAsc());m.addAttribute("categoryRows",catalog.categories(a,categoryQuery));
        m.addAttribute("branches",branches.findAll());m.addAttribute("editing",edit==null?null:dishes.findById(edit).orElseThrow(()->ManagementException.missing("Món không còn tồn tại.")));m.addAttribute("cloudReady",cloud.isConfigured());
        m.addAttribute("q",q);m.addAttribute("categoryId",categoryId);m.addAttribute("state",state);m.addAttribute("branchId",branchId);
        m.addAttribute("available",available);m.addAttribute("sort",sort);m.addAttribute("size",size);m.addAttribute("categoryQuery",categoryQuery);m.addAttribute("pageTitle","Thực đơn & danh mục");return "admin-menu";
    }
    @PostMapping("/admin/menu") String dish(@AuthenticationPrincipal Actor a,@Valid @ModelAttribute AdminService.DishForm f,
            org.springframework.validation.BindingResult validation,Model m,jakarta.servlet.http.HttpServletResponse response,RedirectAttributes r) {
        try {
            if(validation.hasErrors()) throw new BusinessException("Kiểm tra tên, giá, danh mục và mô tả món.");
            admin.dish(a,f);r.addFlashAttribute("success","Đã lưu món ăn.");return "redirect:/admin/menu";
        } catch(BusinessException e) {
            menu(a,null,"",null,"all",null,"all","name",0,10,"",m);
            var input=new Dish();input.setId(f.id());input.setName(f.name());input.setPrice(f.price());input.setDescription(f.description());input.setActive(f.active());
            input.setCategory(f.categoryId()==null?new Category():categories.findById(f.categoryId()).orElseGet(Category::new));
            if(f.id()!=null) dishes.findById(f.id()).ifPresent(d->input.setImageUrl(d.getImageUrl()));
            m.addAttribute("editing",input);m.addAttribute("error",e.getMessage());response.setStatus(managementStatus(e));return "admin-menu";
        }
    }
    @PostMapping("/admin/dishes/{id}/delete") String deleteDish(@AuthenticationPrincipal Actor a,@PathVariable Long id,RedirectAttributes r) {
        return removeDishes(a,java.util.List.of(id),r);
    }
    @PostMapping("/admin/dishes/delete") String deleteDishes(@AuthenticationPrincipal Actor a,@RequestParam(required=false) java.util.List<Long> ids,RedirectAttributes r) {
        return removeDishes(a,ids,r);
    }
    private String removeDishes(Actor a,java.util.List<Long> ids,RedirectAttributes r) {
        try {
            var result=removal.deleteDishes(a,ids);
            r.addFlashAttribute(result.removedCount()>0?"success":"error",result.message());
            r.addFlashAttribute("removalResults",result.items());
        } catch(BusinessException e) {r.addFlashAttribute("error",e.getMessage());}
        return "redirect:/admin/menu";
    }
    @PostMapping("/admin/categories") String category(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long id,@RequestParam String name,@RequestParam(defaultValue="0") int sortOrder,RedirectAttributes r){try{admin.category(a,id,name,sortOrder);r.addFlashAttribute("success","Đã lưu danh mục.");}catch(BusinessException|org.springframework.dao.DataIntegrityViolationException e){r.addFlashAttribute("error",e instanceof BusinessException?e.getMessage():"Tên danh mục đã tồn tại. Vui lòng dùng tên khác.");}return "redirect:/admin/menu#categories";}
    @PostMapping("/admin/categories/{id}/delete") String deleteCategory(@AuthenticationPrincipal Actor a,@PathVariable Long id,RedirectAttributes r){
        try{admin.deleteCategory(a,id);r.addFlashAttribute("success","Đã xóa danh mục trống.");}
        catch(BusinessException|org.springframework.dao.DataIntegrityViolationException e){r.addFlashAttribute("error",e instanceof BusinessException?e.getMessage():"Danh mục đang được sử dụng. Hãy tải lại và chuyển các món trước khi xóa.");}
        return "redirect:/admin/menu#categories";
    }
    @PostMapping("/admin/menu/states") String states(@AuthenticationPrincipal Actor a,@RequestParam(required=false) java.util.List<Long> ids,@RequestParam boolean active,RedirectAttributes r){
        try{admin.dishStates(a,ids,active);r.addFlashAttribute("success","Đã cập nhật trạng thái các món đã chọn.");}
        catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/menu";
    }
    @PostMapping("/admin/dishes/{id}/image") String image(@AuthenticationPrincipal Actor a,@PathVariable Long id,@RequestParam MultipartFile image,RedirectAttributes r){cloud.upload(a,id,image);r.addFlashAttribute("success","Đã cập nhật ảnh món qua Cloudinary.");return "redirect:/admin/menu?edit="+id;}
    @GetMapping("/admin/users") String users(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long edit,
            @RequestParam(defaultValue="") String q,@RequestParam(required=false) Role role,@RequestParam(required=false) Long branchId,
            @RequestParam(defaultValue="all") String state,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,Model m){
        var result=staff.search(a,q,role,branchId,state,page,size);m.addAttribute("users",result.getContent());m.addAttribute("userPage",result);
        m.addAttribute("branches",branches.findAll());m.addAttribute("staffRoles",AdminStaff.ROLES);var editing=edit==null?null:users.findById(edit).orElseThrow();
        if(editing!=null&&!AdminStaff.ROLES.contains(editing.getRole())) throw new BusinessException("Tài khoản cá nhân không thuộc quản trị nhân sự.");
        m.addAttribute("editing",editing);m.addAttribute("q",q);m.addAttribute("filterRole",role);m.addAttribute("branchId",branchId);m.addAttribute("state",state);m.addAttribute("size",size);m.addAttribute("pageTitle","Nhân viên & phân quyền");return "admin-users";
    }
    @PostMapping("/admin/users") String user(@AuthenticationPrincipal Actor a,@Valid @ModelAttribute AdminService.UserForm f,RedirectAttributes r){
        try{admin.user(a,f);r.addFlashAttribute("success","Đã lưu tài khoản và thu hồi các phiên đăng nhập cũ.");}
        catch(BusinessException|org.springframework.dao.DataIntegrityViolationException e){r.addFlashAttribute("error",e instanceof BusinessException?e.getMessage():"Tên đăng nhập đã tồn tại. Hãy chọn tên khác.");}
        return "redirect:/admin/users";
    }
    @PostMapping("/admin/users/{id}/state") String userState(@AuthenticationPrincipal Actor a,@PathVariable Long id,@RequestParam boolean active,RedirectAttributes r){
        try{admin.userState(a,id,active);r.addFlashAttribute("success",active?"Đã mở khóa tài khoản.":"Đã khóa tài khoản và thu hồi phiên đăng nhập.");}
        catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}return "redirect:/admin/users";
    }
    @GetMapping("/admin/audit") String audit(Model m){m.addAttribute("logs",audit.findTop100ByOrderByCreatedAtDesc());return "audit";}
}
