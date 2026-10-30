package vn.utetaste.web;

import java.time.LocalDate;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.utetaste.domain.OrderStatus;
import vn.utetaste.security.*;
import vn.utetaste.service.*;

@Controller @RequiredArgsConstructor
public class MemberController {
    private final MemberService members;private final MemberCookieContext context;
    private String screen(String screen,Actor a,HttpServletRequest request,Model m){m.addAttribute("screen",screen);m.addAttribute("profile",members.profile(a));m.addAttribute("qr",context.table(request).orElse(null));return "member";}
    @GetMapping("/account/profile") String profile(@AuthenticationPrincipal Actor a,HttpServletRequest request,Model m){return screen("profile",a,request,m);}
    @PostMapping("/account/profile") String profile(@AuthenticationPrincipal Actor a,@Valid @ModelAttribute MemberService.ProfileForm form,BindingResult errors,RedirectAttributes flash){
        try {if(errors.hasErrors())throw new BusinessException("Họ tên bắt buộc, tối đa 100 ký tự; số điện thoại tối đa 24 ký tự nhập.");members.updateProfile(a,form);flash.addFlashAttribute("success","Đã cập nhật thông tin của bạn.");}
        catch(BusinessException e){flash.addFlashAttribute("message",e.getMessage());}
        return "redirect:/account/profile";
    }
    @GetMapping("/account/security") String security(@AuthenticationPrincipal Actor a,HttpServletRequest request,Model m){return screen("security",a,request,m);}
    @GetMapping("/account/orders") String orders(@AuthenticationPrincipal Actor a,HttpServletRequest request,Model m,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) OrderStatus status,@RequestParam(defaultValue="newest") String sort,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size){
        m.addAttribute("orders",members.history(a,from,to,status,sort,page,size));m.addAttribute("from",from);m.addAttribute("to",to);m.addAttribute("status",status);m.addAttribute("sort",sort);m.addAttribute("statuses",OrderStatus.values());return screen("orders",a,request,m);
    }
    @GetMapping({"/account/orders/{id}","/account/orders/{id}/receipt"}) String detail(@AuthenticationPrincipal Actor a,@PathVariable Long id,HttpServletRequest request,Model m){m.addAttribute("detail",members.detail(a,id));return screen("detail",a,request,m);}
    @GetMapping("/account/favorites") String favorites(@AuthenticationPrincipal Actor a,HttpServletRequest request,Model m,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size){m.addAttribute("favorites",members.favorites(a,context.table(request).orElse(null),page,size));return screen("favorites",a,request,m);}
    @PostMapping("/account/favorites/{id}") String favorite(@AuthenticationPrincipal Actor a,@PathVariable Long id,@RequestParam boolean selected,RedirectAttributes flash){members.setFavorite(a,id,selected);flash.addFlashAttribute("success",selected?"Đã lưu món yêu thích.":"Đã bỏ món yêu thích.");return "redirect:/account/favorites";}
    public record FavoriteRequest(@NotNull Boolean selected) {}
    @PostMapping("/api/member/favorites/{id}") @ResponseBody Object favorite(@AuthenticationPrincipal Actor a,@PathVariable Long id,@Valid @RequestBody FavoriteRequest form){members.setFavorite(a,id,form.selected());return Map.of("selected",form.selected(),"message",form.selected()?"Đã lưu món yêu thích.":"Đã bỏ món yêu thích.");}
    @GetMapping("/account/favorites/{id}") String favorite(@AuthenticationPrincipal Actor a,@PathVariable Long id,HttpServletRequest request,Model m){m.addAttribute("favorite",members.favoriteDetail(a,id,context.table(request).orElse(null)));return screen("dish",a,request,m);}
    @GetMapping("/account/favorites/{id}/choose") String choose(@AuthenticationPrincipal Actor a,@PathVariable Long id,HttpServletRequest request,RedirectAttributes flash){
        try{return "redirect:/guest/menu?pick="+members.choose(a,id,context.table(request).orElse(null))+"#cart-panel";}
        catch(BusinessException e){flash.addFlashAttribute("message",e.getMessage());return "redirect:/account/favorites";}
    }
    @GetMapping("/account/points") String points(@AuthenticationPrincipal Actor a,HttpServletRequest request,Model m,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size){m.addAttribute("balance",members.balance(a));m.addAttribute("entries",members.points(a,page,size));return screen("points",a,request,m);}
}
