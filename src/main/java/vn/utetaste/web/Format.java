package vn.utetaste.web;
import java.math.*;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.utetaste.domain.*;
import vn.utetaste.service.Reports;
@Component("fmt")
public class Format {
    private static final Set<String> DEMO_PHOTOS=Set.of("chicken","beef","pasta","rice","salad","fries","tea","lemon","dessert","cake");
    /** Resolve old seeded illustration URLs without rewriting existing database rows. */
    public String image(String url) {
        if(url==null||url.isBlank()||url.equals("/images/dish.svg")) return "/images/food/chicken.jpg";
        if(url.startsWith("/images/")&&url.endsWith(".svg")) {
            String name=url.substring("/images/".length(),url.length()-4);
            if(DEMO_PHOTOS.contains(name)) return "/images/food/"+name+".jpg";
        }
        return url;
    }
    public String money(BigDecimal n){return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(n==null?BigDecimal.ZERO:n)+" ₫";}
    public String time(OffsetDateTime t){return t==null?"—":t.atZoneSameInstant(Reports.ZONE).format(DateTimeFormatter.ofPattern("HH:mm · dd/MM/yyyy"));}
    public String status(OrderStatus s){return switch(s){case NEW->"Chờ xác nhận";case CONFIRMED->"Đã xác nhận";case COOKING->"Đang chế biến";case READY->"Chờ phục vụ";case SERVED->"Đã phục vụ";case CANCELLED->"Đã hủy";};}
    public String role(Role r){return switch(r){case ADMIN->"Quản trị viên";case MANAGER->"Quản lý";case WAITER->"Phục vụ";case CHEF->"Đầu bếp";case GUEST->"Khách tại bàn";case USER->"Tài khoản cá nhân";};}
    public String method(PaymentMethod m){return m==PaymentMethod.CASH?"Tiền mặt":"Chuyển khoản (nhân viên xác nhận)";}
    public String action(String action) {
        if(action.startsWith("ORDER_"))try{return status(OrderStatus.valueOf(action.substring(6)));}catch(IllegalArgumentException ignored){}
        return switch(action){case "PLACE_ORDER"->"Khách gửi đơn";case "OPEN_TABLE"->"Mở bàn";case "CHECKOUT"->"Thu tiền";case "CLOSE_EMPTY"->"Đóng bàn trống";case "SAVE_DISH"->"Lưu món";case "ENABLE_DISH"->"Bật bán món";case "DISABLE_DISH"->"Ngừng bán món";case "SAVE_CATEGORY"->"Lưu danh mục";case "DELETE_CATEGORY"->"Xóa danh mục";case "ENABLE_USER"->"Mở khóa nhân viên";case "DISABLE_USER"->"Khóa nhân viên";case "SAVE_USER"->"Cập nhật nhân viên";case "UPLOAD_DISH_IMAGE"->"Cập nhật ảnh món";case "SAVE_BRANCH"->"Lưu chi nhánh";case "BRANCH_STATE"->"Đổi trạng thái chi nhánh";case "DELETE_BRANCH"->"Xóa chi nhánh trống";case "DELETE_DISH"->"Xóa món chưa sử dụng";case "SAVE_TABLE"->"Lưu bàn";case "ROTATE_QR"->"Đổi mã QR";case "DISH_AVAILABILITY"->"Đổi tình trạng món";case "CHANGE_PASSWORD"->"Đổi mật khẩu";default->action;};
    }
}
