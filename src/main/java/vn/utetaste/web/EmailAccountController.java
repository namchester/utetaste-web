package vn.utetaste.web;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.utetaste.repository.AppUserRepository;
import vn.utetaste.security.Actor;
import vn.utetaste.service.*;

@Controller @RequiredArgsConstructor
public class EmailAccountController {
    private final EmailAccountService accounts;
    private final AppUserRepository users;
    private final MemberService members;private final vn.utetaste.security.MemberCookieContext memberContext;
    @Value("${app.cookie-secure}") private boolean secure;
    @GetMapping("/register") String register() {return "register";}
    @PostMapping("/register") String register(@RequestParam String fullName,@RequestParam String email,
            @RequestParam String password,@RequestParam String confirmPassword,HttpServletRequest request,Model model) {
        try {accounts.register(fullName,email,password,confirmPassword,request.getRemoteAddr());model.addAttribute("sent",true);}
        catch(BusinessException e) {model.addAttribute("message",e.getMessage());model.addAttribute("email",email);return "register";}
        model.addAttribute("email",EmailAccountService.normalize(email));return "verify-email";
    }
    @GetMapping("/verify-email") String verify() {return "verify-email";}
    @PostMapping("/verify-email") String verify(@RequestParam String email,@RequestParam String code,
            HttpServletRequest request,Model model) {
        try {accounts.verifyEmail(email,code,request.getRemoteAddr());return "redirect:/login?verified";}
        catch(BusinessException e) {model.addAttribute("message",e.getMessage());model.addAttribute("email",email);return "verify-email";}
    }
    @PostMapping("/verify-email/resend") String resend(@RequestParam String email,HttpServletRequest request,Model model) {
        try {accounts.resendVerification(email,request.getRemoteAddr());model.addAttribute("success",EmailAccountService.RECOVERY_MESSAGE);}
        catch(BusinessException e) {model.addAttribute("message",e.getMessage());}
        model.addAttribute("email",email);return "verify-email";
    }
    @GetMapping("/forgot-password") String forgot() {return "forgot-password";}
    @PostMapping("/forgot-password") String forgot(@RequestParam String email,HttpServletRequest request,Model model) {
        try {accounts.requestReset(email,request.getRemoteAddr());}
        catch(BusinessException ignored) { }
        model.addAttribute("success",EmailAccountService.RECOVERY_MESSAGE);model.addAttribute("email",email);
        return "forgot-password";
    }
    @PostMapping("/forgot-password/verify") String verifyReset(@RequestParam String email,@RequestParam String code,
            HttpServletRequest request,HttpServletResponse response,Model model) {
        try {String grant=accounts.verifyReset(email,code,request.getRemoteAddr());resetCookie(response,grant,300);return "redirect:/reset-password";}
        catch(BusinessException e) {model.addAttribute("message",e.getMessage());model.addAttribute("email",email);return "forgot-password";}
    }
    @GetMapping("/reset-password") String reset(@CookieValue(name="UT_RESET",required=false) String grant,Model model) {
        if(!accounts.hasResetGrant(grant)) {model.addAttribute("message","Hãy xác minh OTP trước khi đặt lại mật khẩu.");return "forgot-password";}
        return "reset-password";
    }
    @PostMapping("/reset-password") String reset(@CookieValue(name="UT_RESET",required=false) String grant,
            @RequestParam String password,@RequestParam String confirmPassword,HttpServletResponse response,Model model) {
        try {accounts.resetPassword(grant,password,confirmPassword);resetCookie(response,"",0);return "redirect:/login?changed";}
        catch(BusinessException e) {model.addAttribute("message",e.getMessage());return "reset-password";}
    }
    @GetMapping("/account") String account(@AuthenticationPrincipal Actor actor,HttpServletRequest request,Model model) {
        if(actor.role()==vn.utetaste.domain.Role.USER){model.addAttribute("overview",members.overview(actor,memberContext.table(request).orElse(null)));model.addAttribute("screen","overview");model.addAttribute("qr",memberContext.table(request).orElse(null));return "member";}
        model.addAttribute("profile",users.findById(actor.id()).orElseThrow());return "account";
    }
    @PostMapping("/account/email") String link(@AuthenticationPrincipal Actor actor,@RequestParam String email,
            @RequestParam String currentPassword,HttpServletRequest request,RedirectAttributes flash) {
        try {accounts.requestLink(actor,email,currentPassword,request.getRemoteAddr());flash.addFlashAttribute("success","Đã gửi mã xác minh đến email mới. Email hiện tại vẫn được giữ đến khi xác minh thành công.");}
        catch(BusinessException e) {flash.addFlashAttribute("message",e.getMessage());}
        return actor.role()==vn.utetaste.domain.Role.USER?"redirect:/account/security":"redirect:/account";
    }
    @PostMapping("/account/email/verify") String link(@AuthenticationPrincipal Actor actor,@RequestParam String code,
            HttpServletRequest request,RedirectAttributes flash) {
        try {accounts.verifyLink(actor,code,request.getRemoteAddr());return "redirect:/login?linked";}
        catch(BusinessException e) {flash.addFlashAttribute("message",e.getMessage());return actor.role()==vn.utetaste.domain.Role.USER?"redirect:/account/security":"redirect:/account";}
    }
    private void resetCookie(HttpServletResponse response,String token,int seconds) {
        response.addHeader("Set-Cookie",ResponseCookie.from("UT_RESET",token).path("/reset-password")
                .httpOnly(true).secure(secure).sameSite("Strict").maxAge(seconds).build().toString());
    }
}
