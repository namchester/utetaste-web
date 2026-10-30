package vn.utetaste.security;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.security.oauth2.jwt.JwtException;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;

@Component @RequiredArgsConstructor
public class MemberCookieContext {
    private final JwtService jwt;private final AppUserRepository users;private final DiningSessionRepository sessions;
    public Optional<MemberIdentity> member(HttpServletRequest request) {
        String token=cookie(request,"UT_ACCESS");if(token==null)return Optional.empty();
        try {
            var claims=jwt.decode(token);String subject=claims.getSubject();Object version=claims.getClaims().get("ver");
            if(subject==null||!subject.matches("staff:[0-9]{1,18}")||!(version instanceof Number number))return Optional.empty();
            return users.findById(Long.valueOf(subject.substring(6)))
                .filter(u->u.getRole()==Role.USER&&u.isActive()&&u.isEmailVerified()&&u.getEmail()!=null&&u.getTokenVersion()==number.intValue())
                .map(u->new MemberIdentity(u.getId(),u.getFullName(),u.getTokenVersion()));
        }catch(JwtException|IllegalArgumentException e){return Optional.empty();}
    }
    public record QrContext(Long sessionId,Long branchId,String branch,String table) {}
    public Optional<QrContext> table(HttpServletRequest request) {
        String token=cookie(request,"UT_GUEST");if(token==null)return Optional.empty();
        try {
            var claims=jwt.decode(token);String subject=claims.getSubject();
            if(subject==null||!subject.matches("guest:[0-9]{1,18}"))return Optional.empty();
            return sessions.findById(Long.valueOf(subject.substring(6))).filter(s->s.isOpen()&&s.getTable().isActive()&&s.getTable().getBranch().isActive())
                .map(s->new QrContext(s.getId(),s.getTable().getBranch().getId(),s.getTable().getBranch().getName(),s.getTable().getLabel()));
        }catch(JwtException|IllegalArgumentException e){return Optional.empty();}
    }
    private String cookie(HttpServletRequest request,String name){if(request.getCookies()!=null)for(var c:request.getCookies())if(name.equals(c.getName()))return c.getValue();return null;}
}
