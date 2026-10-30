package vn.utetaste.web;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import vn.utetaste.domain.OrderStatus;
import vn.utetaste.repository.BranchRepository;
import vn.utetaste.security.Actor;
import vn.utetaste.service.AdminQueries;

@Controller @RequiredArgsConstructor
public class AdminOrdersController {
    private final AdminQueries queries;private final BranchRepository branches;
    @GetMapping("/admin/orders") String list(@AuthenticationPrincipal Actor a,
            @RequestParam(defaultValue="") String q,@RequestParam(required=false) Long branchId,
            @RequestParam(required=false) OrderStatus status,@RequestParam(required=false) LocalDate from,
            @RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="10") int size,Model m) {
        var period=AdminQueries.period(from,to);var result=queries.searchOrders(a,q,branchId,status,period,page,size);
        m.addAttribute("orderPage",result.page());m.addAttribute("statuses",result.statuses());m.addAttribute("q",q);
        m.addAttribute("branchId",branchId);m.addAttribute("status",status);m.addAttribute("from",period.from());m.addAttribute("to",period.to());
        m.addAttribute("size",size);m.addAttribute("branches",branches.findAll());m.addAttribute("pageTitle","Đơn hàng toàn hệ thống");return "admin-orders";
    }
    @GetMapping("/admin/orders/{id}") String detail(@AuthenticationPrincipal Actor a,@PathVariable Long id,Model m) {
        m.addAttribute("detail",queries.detail(a,id));m.addAttribute("pageTitle","Chi tiết & lịch sử đơn");return "admin-order-detail";
    }
}
