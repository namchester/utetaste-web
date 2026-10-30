package vn.utetaste.config;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.event.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import vn.utetaste.security.*;
import vn.utetaste.repository.AppUserRepository;
import vn.utetaste.service.LiveEvent;

@Configuration @EnableWebSocket @EnableScheduling
public class LiveUpdates extends TextWebSocketHandler implements WebSocketConfigurer {
    private final Map<String,WebSocketSession> clients=new ConcurrentHashMap<>();
    private final JwtService jwt; private final AppUserRepository users; private final String origin;
    public LiveUpdates(JwtService jwt,AppUserRepository users,@Value("${app.base-url}") String origin) {this.jwt=jwt;this.users=users;this.origin=origin;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this,"/ws/staff","/ws/guest").setAllowedOrigins(origin).addInterceptors(new HandshakeInterceptor() {
            public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler h,Map<String,Object> attrs) {
                if(!(request.getPrincipal() instanceof Authentication auth)||!(auth.getPrincipal() instanceof Actor a)) return false;
                var req=((ServletServerHttpRequest)request).getServletRequest();
                String wanted=a.isGuest()?"UT_GUEST":"UT_ACCESS";
                if(req.getCookies()!=null) for(var c:req.getCookies()) if(c.getName().equals(wanted)) {
                    var claims=jwt.decode(c.getValue());attrs.put("expires",claims.getExpiresAt());attrs.put("version",((Number)claims.getClaim("ver")).intValue());return true;
                }
                return false;
            }
            public void afterHandshake(ServerHttpRequest r,ServerHttpResponse s,WebSocketHandler h,Exception e) {}
        });
    }
    @Override public void afterConnectionEstablished(WebSocketSession s) {clients.put(s.getId(),new ConcurrentWebSocketSessionDecorator(s,5000,32768));}
    @Override public void afterConnectionClosed(WebSocketSession s,CloseStatus status) {clients.remove(s.getId());}
    @Override protected void handleTextMessage(WebSocketSession s,TextMessage message) throws Exception {
        // The socket is notification-only; all writes use CSRF-protected HTTP endpoints.
        if(message.getPayloadLength()>16) s.close(CloseStatus.POLICY_VIOLATION);
    }
    private Actor actor(WebSocketSession s) {return (Actor)((Authentication)s.getPrincipal()).getPrincipal();}
    private boolean valid(WebSocketSession s) {
        if(!s.isOpen()||Instant.now().isAfter((Instant)s.getAttributes().get("expires"))) return false;
        var a=actor(s);if(a.isGuest()) return true;
        return users.findById(a.id()).filter(u->u.isActive()&&u.getTokenVersion()==(Integer)s.getAttributes().get("version")&&u.getRole()==a.role()&&(a.isAdmin()||(u.getBranch()!=null&&u.getBranch().isActive()&&Objects.equals(u.getBranch().getId(),a.branchId())))).isPresent();
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void publish(LiveEvent event) {
        for(var s:clients.values()) {
            var a=actor(s);
            if(a.isGuest()?Objects.equals(a.sessionId(),event.sessionId()):(a.isAdmin()||Objects.equals(a.branchId(),event.branchId())))
                send(s,"{\"type\":\""+event.type()+"\"}");
        }
    }
    @Scheduled(fixedDelay=30000) public void heartbeat() {for(var s:clients.values()) send(s,"{\"type\":\"heartbeat\"}");}
    private void send(WebSocketSession s,String text) {
        try {if(!valid(s)){s.close(CloseStatus.POLICY_VIOLATION);clients.remove(s.getId());return;}s.sendMessage(new TextMessage(text));}
        catch(Exception e) {clients.remove(s.getId());try{s.close();}catch(Exception ignored){}}
    }
}
