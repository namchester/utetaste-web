package vn.utetaste.service;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.RestaurantTableRepository;
import vn.utetaste.security.Actor;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class TableManagement {
    private final OrderService ops;private final RestaurantTableRepository tables;
    public record Overview(List<OrderService.TableView> rows,long active,long occupied,long free,long inactive,int utilization) {}
    public Overview overview(Actor a,Long branch,String state) {
        ops.roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER);
        if(!Set.of("all","occupied","free","inactive").contains(state)) throw new BusinessException("Trạng thái bàn không hợp lệ.");
        var all=ops.tableViews(a,branch);long active=all.stream().filter(v->v.table().isActive()).count();
        long occupied=all.stream().filter(v->v.table().isActive()&&v.session()!=null).count();
        var rows=all.stream().filter(v->switch(state){case "occupied"->v.table().isActive()&&v.session()!=null;case "free"->v.table().isActive()&&v.session()==null;case "inactive"->!v.table().isActive();default->true;}).toList();
        return new Overview(rows,active,occupied,active-occupied,all.size()-active,active==0?0:(int)Math.round(100d*occupied/active));
    }
    public List<RestaurantTable> printable(Actor a,List<Long> ids) {
        ops.roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER);
        if(ids==null||ids.isEmpty()||ids.size()>50||ids.stream().anyMatch(Objects::isNull)) throw new BusinessException("Chọn từ 1 đến 50 bàn để in.");
        var selected=tables.findAllById(ids.stream().distinct().toList());
        if(selected.size()!=ids.stream().distinct().count()) throw new BusinessException("Một bàn đã chọn không còn tồn tại.");
        selected.forEach(t->ops.checkBranch(a,t.getBranch().getId()));
        return selected.stream().sorted(Comparator.comparing((RestaurantTable t)->t.getBranch().getName()).thenComparing(RestaurantTable::getLabel).thenComparing(RestaurantTable::getId)).toList();
    }
}
