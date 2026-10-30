package vn.utetaste.security;
import java.security.Principal;
import vn.utetaste.domain.Role;
public record Actor(Long id, String username, String displayName, Role role, Long branchId, Long sessionId) implements Principal {
    @Override public String getName() { return username; }
    public boolean isAdmin() { return role == Role.ADMIN; }
    public boolean isGuest() { return role == Role.GUEST; }
}
