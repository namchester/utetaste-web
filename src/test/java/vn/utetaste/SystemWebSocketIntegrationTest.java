package vn.utetaste;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import vn.utetaste.config.LiveUpdates;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.*;
import vn.utetaste.service.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")
class SystemWebSocketIntegrationTest {
    @LocalServerPort int port;
    @Autowired OrderService ops;@Autowired AdminService admin;@Autowired AppUserRepository users;
    @Autowired RestaurantTableRepository tables;@Autowired BranchRepository branches;@Autowired DishRepository dishes;
    @Autowired JwtService jwt;@Autowired LiveUpdates live;
    Actor actor(String name){var u=users.findByUsername(name).orElseThrow();return new Actor(u.getId(),u.getUsername(),u.getFullName(),u.getRole(),u.getBranch()==null?null:u.getBranch().getId(),null);}
    DiningSession session(Long branch){var t=new RestaurantTable();t.setBranch(branches.findById(branch).orElseThrow());t.setLabel("WS-"+UUID.randomUUID().toString().substring(0,8));t.setCapacity(4);tables.saveAndFlush(t);return ops.openTable(actor("admin"),t.getId());}
    static class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();StringBuilder partial=new StringBuilder();
        @Override public void onOpen(WebSocket ws){ws.request(1);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last){partial.append(data);if(last){messages.add(partial.toString());partial=new StringBuilder();}ws.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket ws,int code,String reason){closed.complete(code);return null;}
    }
    WebSocket connect(HttpClient client,String path,String cookie,Listener listener) throws Exception {return client.newWebSocketBuilder().header("Cookie",cookie).buildAsync(URI.create("ws://localhost:"+port+path),listener).get(10,TimeUnit.SECONDS);}
    String access(String name){return "UT_ACCESS="+jwt.staff(users.findByUsername(name).orElseThrow());}
    @Test void eventsStayWithinStaffBranchAndGuestSession() throws Exception {
        var s=session(actor("waiter").branchId());var other=session(actor("waiter").branchId());
        var staff=new Listener();var otherBranch=new Listener();var guest=new Listener();var otherGuest=new Listener();
        try(var client=HttpClient.newHttpClient()) {
            var sockets=new ArrayList<WebSocket>();
            try {
                sockets.add(connect(client,"/ws/staff",access("waiter"),staff));sockets.add(connect(client,"/ws/staff",access("waiter2"),otherBranch));
                sockets.add(connect(client,"/ws/guest","UT_GUEST="+jwt.guest(s),guest));sockets.add(connect(client,"/ws/guest","UT_GUEST="+jwt.guest(other),otherGuest));
                var g=new Actor(null,"guest:"+s.getId(),s.getTable().getLabel(),Role.GUEST,s.getTable().getBranch().getId(),s.getId());
                ops.place(g,new OrderService.PlaceRequest(UUID.randomUUID().toString(),List.of(new OrderService.Line(dishes.findAllByOrderByNameAsc().getFirst().getId(),1)),""));
                assertThat(staff.messages.poll(5,TimeUnit.SECONDS)).contains("orders.changed");assertThat(guest.messages.poll(5,TimeUnit.SECONDS)).contains("orders.changed");
                assertThat(otherBranch.messages.stream().filter(m->!m.contains("heartbeat"))).isEmpty();assertThat(otherGuest.messages.stream().filter(m->!m.contains("heartbeat"))).isEmpty();
                // A heartbeat acts as a delivery barrier for sockets that must not see the order event.
                live.heartbeat();assertThat(otherBranch.messages.poll(5,TimeUnit.SECONDS)).contains("heartbeat");assertThat(otherGuest.messages.poll(5,TimeUnit.SECONDS)).contains("heartbeat");
            }finally{for(var socket:sockets)socket.sendClose(WebSocket.NORMAL_CLOSURE,"test complete").get(5,TimeUnit.SECONDS);}
        }
    }
    @Test void revokingStaffTokenClosesAnExistingSocket() throws Exception {
        String name="ws_"+UUID.randomUUID();admin.user(actor("admin"),new AdminService.UserForm(null,name,"WS test",Role.WAITER,actor("waiter").branchId(),"WebSocket@Test2026",true));
        var listener=new Listener();try(var client=HttpClient.newHttpClient()) {
            var socket=connect(client,"/ws/staff",access(name),listener);
            try {admin.userState(actor("admin"),users.findByUsername(name).orElseThrow().getId(),false);live.heartbeat();assertThat(listener.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);}
            finally {socket.abort();}
        }
    }
    @Test void unauthenticatedAndForeignOriginHandshakesAreRejected() {
        try(var client=HttpClient.newHttpClient()) {
            assertThatThrownBy(()->client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws/staff"),new Listener()).get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(WebSocketHandshakeException.class);
            assertThatThrownBy(()->client.newWebSocketBuilder().header("Cookie",access("waiter")).header("Origin","https://untrusted.example").buildAsync(URI.create("ws://localhost:"+port+"/ws/staff"),new Listener()).get(5,TimeUnit.SECONDS)).hasCauseInstanceOf(WebSocketHandshakeException.class);
        }
    }
}
