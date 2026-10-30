package vn.utetaste.service;

import java.util.*;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AdminStaff {
    private final AppUserRepository users;private final AdminCatalog catalog;private final OrderService ops;
    public static final List<Role> ROLES=List.of(Role.ADMIN,Role.MANAGER,Role.WAITER,Role.CHEF);
    public Page<AppUser> search(Actor actor,String q,Role role,Long branchId,String state,int page,int size) {
        ops.roles(actor,Role.ADMIN);catalog.validateBranch(branchId);
        if(role!=null&&!ROLES.contains(role)) throw new BusinessException("Vai trò nhân viên không hợp lệ.");
        if(!Set.of("all","active","inactive").contains(state)) throw new BusinessException("Trạng thái nhân viên không hợp lệ.");
        String query=AdminCatalog.search(q);
        Specification<AppUser> spec=(u,cq,cb)->{
            List<Predicate> filters=new ArrayList<>();filters.add(u.get("role").in(ROLES));
            if(!query.isEmpty()) filters.add(cb.or(cb.like(cb.lower(u.get("username")),AdminCatalog.like(query),'\\'),cb.like(cb.lower(u.get("fullName")),AdminCatalog.like(query),'\\')));
            if(role!=null) filters.add(cb.equal(u.get("role"),role));
            if(branchId!=null) filters.add(cb.equal(u.get("branch").get("id"),branchId));
            if(!state.equals("all")) filters.add(cb.equal(u.get("active"),state.equals("active")));
            return cb.and(filters.toArray(Predicate[]::new));
        };
        return users.findAll(spec,AdminCatalog.paging(page,size,Sort.by("fullName","id")));
    }
}
