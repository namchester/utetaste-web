package vn.utetaste.security;
import java.io.IOException;
import java.util.List;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.RequiredArgsConstructor;
import vn.utetaste.domain.Role;
import vn.utetaste.repository.*;
@Component @RequiredArgsConstructor
public class JwtCookieFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final AppUserRepository users;
    private final DiningSessionRepository sessions;
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String uri=req.getRequestURI();
        boolean guestPath=uri.startsWith("/guest")||uri.startsWith("/api/guest")||uri.startsWith("/ws/guest");
        String token=cookie(req,guestPath?"UT_GUEST":"UT_ACCESS");
        if(token!=null) {
            try {
                var claims=jwt.decode(token); String subject=claims.getSubject(); Actor actor=null;
                if(!guestPath && subject.startsWith("staff:")) {
                    var u=users.findById(Long.valueOf(subject.substring(6))).orElse(null);
                    Number version=claims.getClaim("ver");
                    if(u!=null && u.isActive() && version!=null && version.intValue()==u.getTokenVersion() && (u.getRole()==Role.ADMIN || (u.getRole()==Role.USER&&u.isEmailVerified()&&u.getEmail()!=null) || (u.getRole()!=Role.USER&&u.getRole()!=Role.GUEST&&u.getBranch()!=null && u.getBranch().isActive())))
                        actor=new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);
                } else if(guestPath && subject.startsWith("guest:")) {
                    var s=sessions.findById(Long.valueOf(subject.substring(6))).orElse(null);
                    if(s!=null && s.getTable().getBranch().isActive()) actor=new Actor(null,subject,s.getTable().getLabel(),Role.GUEST,s.getTable().getBranch().getId(),s.getId());
                }
                if(actor!=null) SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_"+actor.role()))));
            } catch(JwtException | IllegalArgumentException ignored) { SecurityContextHolder.clearContext(); }
        }
        chain.doFilter(req,res);
    }
    private String cookie(HttpServletRequest req,String name) {
        if(req.getCookies()!=null) for(var c:req.getCookies()) if(c.getName().equals(name)) return c.getValue(); return null;
    }
}
