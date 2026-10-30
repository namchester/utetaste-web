package vn.utetaste.web;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import vn.utetaste.domain.Role;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
@Controller @RequiredArgsConstructor
public class AuthController {
    private final AppUserRepository users;private final BranchRepository branches;private final PasswordEncoder encoder;private final JwtService jwt;private final OrderService orders;private final AdminService admin;
    private final LoginAttemptLimiter attempts;
    @Value("${app.cookie-secure}") private boolean secure;
    @GetMapping("/") String home(Model model){model.addAttribute("branches",branches.findByActiveTrueOrderByName());return "home";}
    @GetMapping("/login") String login(){return "login";}
    @PostMapping("/login") String login(@RequestParam(defaultValue="") String username,@RequestParam(defaultValue="") String password,HttpServletRequest request,HttpServletResponse response) {
        username=username.strip();
        boolean emailLogin=username.contains("@");
        if(emailLogin) {
            try {username=EmailAccountService.normalize(username);} catch(BusinessException e) {return "redirect:/login?error";}
        }
        if((!emailLogin&&!username.matches("[a-zA-Z0-9_.-]{3,60}"))
            ||password.isEmpty()||password.length()>72||password.getBytes(StandardCharsets.UTF_8).length>72) {
            return "redirect:/login?error";
        }
        String address=request.getRemoteAddr();
        if(attempts.isBlocked(address,username)) return "redirect:/login?locked";
        var u=(emailLogin?users.findByEmail(username):users.findByUsername(username)).orElse(null);
        if(u==null||!u.isActive()||u.getRole()==null||u.getRole()==Role.GUEST
            ||(emailLogin&&!u.isEmailVerified())
            ||(u.getRole()==Role.USER&&(!u.isEmailVerified()||u.getEmail()==null))
            ||(u.getRole()!=Role.ADMIN&&u.getRole()!=Role.USER&&(u.getBranch()==null||!u.getBranch().isActive()))
            ||!encoder.matches(password,u.getPasswordHash())) {
            attempts.failed(address,username);
            return "redirect:/login?error";
        }
        attempts.succeeded(address,username);cookie(response,"UT_ACCESS",jwt.staff(u),8*3600);
        if(u.getRole()==Role.USER) return "redirect:/account";
        return u.getRole()==Role.ADMIN&&branches.count()==0?"redirect:/admin/branches":"redirect:/staff";
    }
    @PostMapping("/logout")
    public String logout(@AuthenticationPrincipal Actor a, HttpServletResponse response) {
        // Commit revocation before reporting success or removing the browser cookie.
        admin.logout(a);
        cookie(response, "UT_ACCESS", "", 0);
        return "redirect:/login?logout";
    }
    @GetMapping("/t/{key}") String table(@PathVariable String key,Model model){model.addAttribute("dining",orders.join(key));model.addAttribute("qrKey",key);return "join";}
    @PostMapping("/t/{key}/join") String join(@PathVariable String key,HttpServletResponse response){cookie(response,"UT_GUEST",jwt.guest(orders.join(key)),6*3600);return "redirect:/guest/menu";}
    @PostMapping("/guest/leave") String leave(HttpServletResponse response){cookie(response,"UT_GUEST","",0);return "redirect:/";}
    @GetMapping("/account/password") String password(Model model){model.addAttribute("pageTitle","Đổi mật khẩu");return "password";}
    @PostMapping("/account/password") String password(@AuthenticationPrincipal Actor a,@RequestParam(defaultValue="") String oldPassword,@RequestParam(defaultValue="") String newPassword,HttpServletResponse response){admin.password(a,oldPassword,newPassword);cookie(response,"UT_ACCESS","",0);return "redirect:/login?changed";}
    private void cookie(HttpServletResponse response,String name,String value,int age){response.addHeader("Set-Cookie",ResponseCookie.from(name,value).httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(age).build().toString());}
}
