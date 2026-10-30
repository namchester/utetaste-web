package vn.utetaste.config;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.service.AdminService;
@Component @RequiredArgsConstructor
public class SeedData {
    private final BranchRepository branches;private final CategoryRepository categories;private final DishRepository dishes;
    private final BranchDishRepository availability;private final RestaurantTableRepository tables;private final AppUserRepository users;private final PasswordEncoder encoder;
    @Value("${app.seed-demo}") private boolean demo;
    @Value("${app.demo-password}") private String demoPassword;
    @Value("${app.admin-password}") private String adminPassword;
    @EventListener(ApplicationReadyEvent.class) @Transactional
    public void seed() {
        if(users.count()>0) return;
        String password=demo?demoPassword:adminPassword;AdminService.validatePassword(password);
        var admin=new AppUser();admin.setUsername("admin");admin.setFullName("Quản trị UTETaste");admin.setPasswordHash(encoder.encode(password));admin.setRole(Role.ADMIN);users.save(admin);
        if(!demo) return;
        var b1=branch("UTETaste · Thủ Đức","1 Võ Văn Ngân, TP. Hồ Chí Minh","0901000001");
        var b2=branch("UTETaste · Quận 7","12 Nguyễn Văn Linh, TP. Hồ Chí Minh","0901000002");
        for(var b:new Branch[]{b1,b2}) {
            String suffix=b==b1?"":"2";
            user("manager"+suffix,"Quản lý "+b.getName(),Role.MANAGER,b,password);
            user("waiter"+suffix,"Phục vụ "+b.getName(),Role.WAITER,b,password);
            user("chef"+suffix,"Bếp "+b.getName(),Role.CHEF,b,password);
            for(int i=1;i<=8;i++){var t=new RestaurantTable();t.setBranch(b);t.setLabel(String.format("Bàn %02d",i));t.setCapacity(i<5?4:6);tables.save(t);}
        }
        var main=category("Món chính",1);var light=category("Khai vị",2);var drink=category("Đồ uống",3);var sweet=category("Tráng miệng",4);
        dish("Cơm gà nướng mật ong",69000,"Gà nướng thơm, cơm dẻo và rau củ theo mùa.",main,"chicken");
        dish("Bò lúc lắc khoai tây",99000,"Bò áp chảo với sốt tiêu, khoai tây và rau xanh.",main,"beef");
        dish("Mì Ý sốt bò bằm",79000,"Mì Ý cùng sốt cà chua và bò bằm đậm vị.",main,"pasta");
        dish("Cơm chiên hải sản",75000,"Cơm chiên tôm mực, trứng và rau củ.",main,"rice");
        dish("Salad vườn xanh",49000,"Rau tươi, cà chua bi và sốt mè rang.",light,"salad");
        dish("Khoai tây chiên",35000,"Khoai tây giòn, dùng kèm sốt nhà làm.",light,"fries");
        dish("Trà đào cam sả",39000,"Trà thanh mát, đào và cam tươi.",drink,"tea");
        dish("Nước chanh tươi",29000,"Chanh tươi pha theo yêu cầu ít hoặc vừa ngọt.",drink,"lemon");
        dish("Panna cotta dâu",45000,"Kem sữa mềm mịn, phủ sốt dâu.",sweet,"dessert");
        dish("Bánh chocolate",49000,"Bánh chocolate mềm, vị cacao đậm.",sweet,"cake");
    }
    private Branch branch(String name,String address,String phone){var b=new Branch();b.setName(name);b.setAddress(address);b.setPhone(phone);return branches.save(b);}
    private Category category(String name,int sort){var c=new Category();c.setName(name);c.setSortOrder(sort);return categories.save(c);}
    private void user(String name,String full,Role role,Branch b,String password){var u=new AppUser();u.setUsername(name);u.setFullName(full);u.setRole(role);u.setBranch(b);u.setPasswordHash(encoder.encode(password));users.save(u);}
    private void dish(String name,int price,String desc,Category category,String image){var d=new Dish();d.setName(name);d.setPrice(BigDecimal.valueOf(price));d.setDescription(desc);d.setCategory(category);d.setImageUrl("/images/food/"+image+".jpg");dishes.save(d);for(var b:branches.findAll()){var bd=new BranchDish();bd.setBranch(b);bd.setDish(d);availability.save(bd);}}
}
