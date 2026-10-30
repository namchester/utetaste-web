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
public class AdminCatalog {
    private final DishRepository dishes;
    private final CategoryRepository categories;
    private final BranchRepository branches;
    private final OrderService ops;
    public record CategoryRow(Category category,long dishCount) {}
    public Page<Dish> menu(Actor actor,String q,Long categoryId,String state,Long branchId,String available,String sort,int page,int size) {
        ops.roles(actor,Role.ADMIN);validateBranch(branchId);
        if(categoryId!=null&&!categories.existsById(categoryId)) throw new BusinessException("Danh mục không tồn tại.");
        if(!Set.of("all","active","inactive").contains(state)||!Set.of("all","yes","no").contains(available)) throw new BusinessException("Bộ lọc trạng thái không hợp lệ.");
        if(branchId==null&&!available.equals("all")) throw new BusinessException("Chọn chi nhánh trước khi lọc tình trạng còn món.");
        String query=search(q);
        Specification<Dish> spec=(root,cq,cb)->{
            List<Predicate> predicates=new ArrayList<>();
            if(!query.isEmpty()) predicates.add(cb.like(cb.lower(root.get("name")),like(query),'\\'));
            if(categoryId!=null) predicates.add(cb.equal(root.get("category").get("id"),categoryId));
            if(!state.equals("all")) predicates.add(cb.equal(root.get("active"),state.equals("active")));
            if(branchId!=null) {
                var sub=cq.subquery(Long.class);var bd=sub.from(BranchDish.class);
                var match=cb.and(cb.equal(bd.get("dish").get("id"),root.get("id")),cb.equal(bd.get("branch").get("id"),branchId));
                if(!available.equals("all")) match=cb.and(match,cb.equal(bd.get("available"),available.equals("yes")));
                sub.select(bd.get("id")).where(match);predicates.add(cb.exists(sub));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Sort order=switch(sort) {
            case "name" -> Sort.by("name").ascending();case "name-desc" -> Sort.by("name").descending();
            case "price" -> Sort.by("price").ascending();case "price-desc" -> Sort.by("price").descending();
            case "state" -> Sort.by("active").descending().and(Sort.by("name"));
            default -> throw new BusinessException("Cách sắp xếp không hợp lệ.");
        };
        return dishes.findAll(spec,paging(page,size,order.and(Sort.by("id"))));
    }
    public List<CategoryRow> categories(Actor actor,String query) {
        ops.roles(actor,Role.ADMIN);String q=search(query);
        Map<Long,Long> counts=new HashMap<>();dishes.categoryCounts().forEach(c->counts.put(c.getCategoryId(),c.getDishCount()));
        return categories.findAllByOrderBySortOrderAscNameAsc().stream()
            .filter(c->c.getName().toLowerCase(Locale.ROOT).contains(q)).map(c->new CategoryRow(c,counts.getOrDefault(c.getId(),0L))).toList();
    }
    public void validateBranch(Long id) {if(id!=null&&!branches.existsById(id)) throw new BusinessException("Chi nhánh không tồn tại.");}
    public static PageRequest paging(int page,int size,Sort sort) {
        if(page<0||page>100000||!Set.of(10,25,50).contains(size)) throw new BusinessException("Trang không hợp lệ; chọn 10, 25 hoặc 50 dòng.");
        return PageRequest.of(page,size,sort);
    }
    public static String search(String value) {String q=value==null?"":value.strip().toLowerCase(Locale.ROOT);if(q.length()>120) throw new BusinessException("Từ khóa tối đa 120 ký tự.");return q;}
    public static String like(String q) {return "%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
}
