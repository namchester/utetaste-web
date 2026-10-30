package vn.utetaste.web;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
import vn.utetaste.service.OrderService;
import vn.utetaste.service.MemberService;
import vn.utetaste.security.MemberCookieContext;
@Controller @RequiredArgsConstructor
public class GuestController {
    private final OrderService ops;private final BranchDishRepository menu;private final CategoryRepository categories;private final InvoiceRepository invoices;
    private final MemberService members;private final MemberCookieContext context;private final FoodOrderRepository orders;
    @GetMapping("/guest/menu") String menu(@AuthenticationPrincipal Actor a,HttpServletRequest request,Model m){
        var s=ops.guestSession(a);var dishes=menu.findByBranchIdOrderByDishName(a.branchId()).stream().filter(x->x.getDish().isActive()).toList();
        var member=context.member(request).orElse(null);m.addAttribute("member",member);
        m.addAttribute("favoriteIds",member==null?Set.of():members.favoriteIds(member,dishes.stream().map(x->x.getDish().getId()).toList()));
        m.addAttribute("dining",s);m.addAttribute("menu",dishes);m.addAttribute("categories",categories.findAllByOrderBySortOrderAscNameAsc());return "menu";
    }
    @GetMapping("/guest/orders") String page(@AuthenticationPrincipal Actor a,HttpServletRequest request,Model m){
        var member=context.member(request).orElse(null);m.addAttribute("member",member);m.addAttribute("dining",ops.guestSession(a));
        m.addAttribute("orders",member==null?ops.guestOrders(a):members.qrOrders(member,a));
        var invoice=invoices.findBySessionId(a.sessionId()).orElse(null);boolean restricted=member!=null||orders.existsBySessionIdAndCustomerIsNotNull(a.sessionId());
        m.addAttribute("invoice",restricted?null:invoice);
        m.addAttribute("memberPaid",restricted&&invoice!=null);return "guest-orders";
    }
    @GetMapping("/api/guest/orders") @ResponseBody Object list(@AuthenticationPrincipal Actor a,HttpServletRequest request){var member=context.member(request).orElse(null);return Map.of("orders",member==null?ops.guestOrders(a):members.qrOrders(member,a),"open",ops.guestSession(a).isOpen());}
    @PostMapping("/api/guest/orders") @ResponseBody Object place(@AuthenticationPrincipal Actor a,@Valid @RequestBody OrderService.PlaceRequest request,HttpServletRequest http){return ops.place(a,request,context.member(http).orElse(null));}
    public record CallRequest(@NotBlank @Size(max=120) String message) {}
    @PostMapping("/api/guest/call") @ResponseBody Object call(@AuthenticationPrincipal Actor a,@Valid @RequestBody CallRequest r){ops.call(a,r.message());return Map.of("message","Nhân viên đã nhận được yêu cầu của bạn.");}
}
