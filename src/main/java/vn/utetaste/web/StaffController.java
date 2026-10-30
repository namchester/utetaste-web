package vn.utetaste.web;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
import vn.utetaste.service.*;
@Controller @RequiredArgsConstructor
public class StaffController {
    private final OrderService ops;private final AdminService admin;private final Reports reports;
    private final AdminQueries adminQueries;
    private final TableManagement tableManagement;
    private final BranchRepository branches;private final RestaurantTableRepository tables;private final DiningSessionRepository sessions;private final InvoiceRepository invoices;private final ServiceCallRepository calls;private final BranchDishRepository menu;
    @Value("${app.base-url}") private String baseUrl;
    private Long branch(Actor a,Long id,Model m) {var b=ops.branchId(a,id);m.addAttribute("branches",a.isAdmin()?branches.findByActiveTrueOrderByName():List.of(branches.findById(b).orElseThrow()));m.addAttribute("branch",branches.findById(b).orElseThrow());m.addAttribute("branchId",b);return b;}
    @GetMapping("/staff") String dashboard(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long branchId,
            @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,Model m) {
        if(a.role()==Role.CHEF) return "redirect:/staff/kitchen";
        if(a.isAdmin()) {
            var period=AdminQueries.period(from,to);m.addAttribute("stats",adminQueries.dashboard(a,branchId,period));
            m.addAttribute("branches",branches.findAll());m.addAttribute("branchId",branchId);m.addAttribute("from",period.from());m.addAttribute("to",period.to());m.addAttribute("pageTitle","Tổng quan quản trị");return "admin-dashboard";
        }
        var b=branch(a,branchId,m);var current=ops.activeOrders(a,b);var tv=ops.tableViews(a,b);var today=LocalDate.now(Reports.ZONE);var paid=reports.invoices(a,b,today,today);
        m.addAttribute("orders",current);m.addAttribute("occupied",tv.stream().filter(t->t.session()!=null).count());m.addAttribute("tableCount",tv.size());m.addAttribute("revenue",paid.stream().map(Invoice::getTotal).reduce(BigDecimal.ZERO,BigDecimal::add));m.addAttribute("invoiceCount",paid.size());m.addAttribute("week",reports.week(a,b));m.addAttribute("calls",calls.findBySessionTableBranchIdAndResolvedAtIsNullOrderByCreatedAt(b));return "dashboard";
    }
    @GetMapping("/staff/tables") String tables(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long branchId,@RequestParam(defaultValue="all") String state,Model m){
        var b=branch(a,branchId,m);var overview=tableManagement.overview(a,b,state);m.addAttribute("tables",overview.rows());m.addAttribute("stats",overview);m.addAttribute("state",state);m.addAttribute("pageTitle","Bàn & mã QR");return "tables";
    }
    @GetMapping("/staff/tables/print") String printTables(@AuthenticationPrincipal Actor a,@RequestParam(required=false) List<Long> ids,Model m){
        var selected=tableManagement.printable(a,ids);
        m.addAttribute("pages",java.util.stream.IntStream.range(0,(selected.size()+3)/4).mapToObj(i->selected.subList(i*4,Math.min(i*4+4,selected.size()))).toList());return "qr-batch";
    }
    @PostMapping("/staff/tables/{id}/open") String open(@AuthenticationPrincipal Actor a,@PathVariable Long id,RedirectAttributes r){var s=ops.openTable(a,id);r.addFlashAttribute("success","Đã mở "+s.getTable().getLabel()+". Khách có thể quét QR.");return "redirect:/staff/tables?branchId="+s.getTable().getBranch().getId();}
    @PostMapping("/staff/tables/save") String saveTable(@AuthenticationPrincipal Actor a,@Valid @ModelAttribute AdminService.TableForm form,RedirectAttributes r){admin.table(a,form);r.addFlashAttribute("success","Đã lưu bàn.");return "redirect:/staff/tables?branchId="+form.branchId();}
    @PostMapping("/staff/tables/{id}/rotate") String rotate(@AuthenticationPrincipal Actor a,@PathVariable Long id,RedirectAttributes r){
        var t=tables.findById(id).orElseThrow();ops.checkBranch(a,t.getBranch().getId());
        try{admin.rotateQr(a,id);r.addFlashAttribute("success","Đã đổi mã QR. Mã cũ hết hiệu lực; hãy in lại mã mới.");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
        return "redirect:/staff/tables?branchId="+t.getBranch().getId();
    }
    @GetMapping("/staff/tables/{id}/qr") String qr(@AuthenticationPrincipal Actor a,@PathVariable Long id,Model m){var t=tables.findById(id).orElseThrow();ops.checkBranch(a,t.getBranch().getId());m.addAttribute("table",t);m.addAttribute("tableUrl",baseUrl+"/t/"+t.getQrKey());return "qr";}
    @GetMapping(value="/staff/tables/{id}/qr.png",produces=MediaType.IMAGE_PNG_VALUE) @ResponseBody byte[] qrImage(@AuthenticationPrincipal Actor a,@PathVariable Long id) throws Exception {var t=tables.findById(id).orElseThrow();ops.checkBranch(a,t.getBranch().getId());var matrix=new QRCodeWriter().encode(baseUrl+"/t/"+t.getQrKey(),BarcodeFormat.QR_CODE,400,400);var out=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(matrix,"PNG",out);return out.toByteArray();}
    @GetMapping("/staff/orders") String orders(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long branchId,Model m){var b=branch(a,branchId,m);m.addAttribute("orders",ops.activeOrders(a,b));m.addAttribute("kitchen",false);return "orders";}
    @GetMapping("/staff/kitchen") String kitchen(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long branchId,Model m){var b=branch(a,branchId,m);m.addAttribute("orders",ops.activeOrders(a,b).stream().filter(o->o.status()!=OrderStatus.SERVED).toList());m.addAttribute("kitchen",true);return "orders";}
    public record StatusRequest(@NotNull OrderStatus status) {}
    @PostMapping("/api/staff/orders/{id}/status") @ResponseBody Object status(@AuthenticationPrincipal Actor a,@PathVariable Long id,@Valid @RequestBody StatusRequest request){return ops.changeStatus(a,id,request.status());}
    @GetMapping("/staff/sessions/{id}") String session(@AuthenticationPrincipal Actor a,@PathVariable Long id,Model m){var s=sessions.findById(id).orElseThrow();ops.checkBranch(a,s.getTable().getBranch().getId());var list=ops.sessionOrders(a,id);m.addAttribute("dining",s);m.addAttribute("orders",list);m.addAttribute("total",list.stream().filter(o->o.status()!=OrderStatus.CANCELLED).map(OrderService.OrderView::total).reduce(BigDecimal.ZERO,BigDecimal::add));m.addAttribute("invoice",invoices.findBySessionId(id).orElse(null));return "session";}
    @PostMapping("/staff/sessions/{id}/checkout") String checkout(@AuthenticationPrincipal Actor a,@PathVariable Long id,@RequestParam(defaultValue="0") int discountPercent,@RequestParam PaymentMethod method){var invoice=ops.checkout(a,id,discountPercent,method);return "redirect:/staff/receipt/"+invoice.getId();}
    @PostMapping("/staff/sessions/{id}/close") String close(@AuthenticationPrincipal Actor a,@PathVariable Long id){ops.closeEmpty(a,id);return "redirect:/staff/tables?branchId="+sessions.findById(id).orElseThrow().getTable().getBranch().getId();}
    @GetMapping("/staff/receipt/{id}") String receipt(@AuthenticationPrincipal Actor a,@PathVariable Long id,Model m){var i=invoices.findById(id).orElseThrow();ops.checkBranch(a,i.getSession().getTable().getBranch().getId());m.addAttribute("invoice",i);m.addAttribute("orders",ops.sessionOrders(a,i.getSession().getId()).stream().filter(o->o.status()!=OrderStatus.CANCELLED).toList());return "receipt";}
    @PostMapping("/staff/calls/{id}/resolve") String resolve(@AuthenticationPrincipal Actor a,@PathVariable Long id){var c=calls.findById(id).orElseThrow();ops.resolveCall(a,id);return "redirect:/staff?branchId="+c.getSession().getTable().getBranch().getId();}
    @GetMapping("/staff/menu") String menu(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long branchId,Model m){var b=branch(a,branchId,m);m.addAttribute("menu",menu.findByBranchIdOrderByDishName(b));return "availability";}
    @PostMapping("/staff/menu/{id}/availability") String available(@AuthenticationPrincipal Actor a,@PathVariable Long id,@RequestParam Long branchId,@RequestParam boolean available){admin.availability(a,branchId,id,available);return "redirect:/staff/menu?branchId="+branchId;}
    @GetMapping("/staff/reports") String report(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long branchId,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,Model m){
        var b=reports.scope(a,branchId);if(to==null) to=LocalDate.now(Reports.ZONE);if(from==null) from=to.withDayOfMonth(1);
        var data=reports.summary(a,b,from,to);m.addAttribute("stats",data);m.addAttribute("invoices",data.invoices());m.addAttribute("topDishes",data.top());m.addAttribute("total",data.total());
        m.addAttribute("branches",a.isAdmin()?branches.findAll():List.of(branches.findById(b).orElseThrow()));m.addAttribute("branchId",b);
        m.addAttribute("from",from);m.addAttribute("to",to);m.addAttribute("pageTitle","Báo cáo kinh doanh");return "reports";
    }
    @GetMapping("/staff/reports/export.csv") @ResponseBody ResponseEntity<byte[]> csv(@AuthenticationPrincipal Actor a,@RequestParam(required=false) Long branchId,@RequestParam LocalDate from,@RequestParam LocalDate to){
        var data=reports.summary(a,branchId,from,to).invoices();var csv=new StringBuilder("\uFEFFMã hóa đơn,Chi nhánh,Bàn,Thời gian (Việt Nam),Tạm tính,Giảm giá,Thực thu,Phương thức\r\n");
        for(var i:data)csv.append(csvCell(i.getCode())).append(',').append(csvCell(i.getSession().getTable().getBranch().getName())).append(',').append(csvCell(i.getSession().getTable().getLabel())).append(',').append(csvCell(i.getPaidAt().atZoneSameInstant(Reports.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))).append(',').append(i.getSubtotal().toPlainString()).append(',').append(i.getDiscountAmount().toPlainString()).append(',').append(i.getTotal().toPlainString()).append(',').append(i.getMethod()).append("\r\n");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=utetaste-revenue.csv").contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    private String csvCell(String value){String s=value;String trimmed=s.stripLeading();if(!s.isEmpty()&&(s.charAt(0)=='\t'||s.charAt(0)=='\r'||s.charAt(0)=='\n'||(!trimmed.isEmpty()&&"=+@-".indexOf(trimmed.charAt(0))>=0)))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
}
