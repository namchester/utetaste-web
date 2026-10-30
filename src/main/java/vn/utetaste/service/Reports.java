package vn.utetaste.service;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.utetaste.domain.*;
import vn.utetaste.repository.*;
import vn.utetaste.security.Actor;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class Reports {
    private final InvoiceRepository invoices;private final FoodOrderRepository orders;private final OrderService ops;private final BranchRepository branches;
    public static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    public record Day(LocalDate date,BigDecimal amount,int percent) {}
    public record TopDish(String name,int quantity,BigDecimal revenue) {}
    public List<Invoice> invoices(Actor a,Long branch,LocalDate from,LocalDate to) {
        ops.roles(a,Role.ADMIN,Role.MANAGER,Role.WAITER);ops.checkBranch(a,branch);
        AdminQueries.period(from,to);
        return invoices.findBySessionTableBranchIdAndPaidAtGreaterThanEqualAndPaidAtLessThanOrderByPaidAtDesc(branch,from.atStartOfDay(ZONE).toOffsetDateTime(),to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    }
    public List<Day> week(Actor a,Long branch) {
        LocalDate today=LocalDate.now(ZONE);var data=invoices(a,branch,today.minusDays(6),today);Map<LocalDate,BigDecimal> totals=new HashMap<>();
        data.forEach(i->totals.merge(i.getPaidAt().atZoneSameInstant(ZONE).toLocalDate(),i.getTotal(),BigDecimal::add));
        BigDecimal max=totals.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE).max(BigDecimal.ONE);
        List<Day> result=new ArrayList<>();for(int n=6;n>=0;n--){var date=today.minusDays(n);var amount=totals.getOrDefault(date,BigDecimal.ZERO);result.add(new Day(date,amount,Math.max(2,amount.multiply(BigDecimal.valueOf(100)).divide(max,0,java.math.RoundingMode.HALF_UP).intValue())));}return result;
    }
    public List<TopDish> top(List<Invoice> data) {
        return data.isEmpty()?List.of():orders.findTopDishes(data.stream().map(i->i.getSession().getId()).distinct().toList());
    }
    public record PaymentTotal(PaymentMethod method,long count,BigDecimal amount) {}
    public record BranchTotal(String name,long count,BigDecimal amount) {}
    public record Summary(List<Invoice> invoices,BigDecimal total,BigDecimal average,long served,long cancelled,
                          List<Day> days,List<TopDish> top,List<PaymentTotal> payments,List<BranchTotal> branches) {}
    public Long scope(Actor a,Long requested) {
        ops.roles(a,Role.ADMIN,Role.MANAGER);
        Long branch=a.isAdmin()?requested:ops.branchId(a,requested);
        if(branch!=null&&!branches.existsById(branch)) throw new BusinessException("Chi nhánh không tồn tại.");
        return branch;
    }
    public Summary summary(Actor a,Long requested,LocalDate from,LocalDate to) {
        var branch=scope(a,requested);var period=AdminQueries.period(from,to);
        var paid=invoices.paid(branch,period.start(),period.end());
        var total=paid.stream().map(Invoice::getTotal).reduce(BigDecimal.ZERO,BigDecimal::add);
        var average=paid.isEmpty()?BigDecimal.ZERO:total.divide(BigDecimal.valueOf(paid.size()),0,java.math.RoundingMode.HALF_UP);
        Map<LocalDate,BigDecimal> daily=new HashMap<>();paid.forEach(i->daily.merge(i.getPaidAt().atZoneSameInstant(ZONE).toLocalDate(),i.getTotal(),BigDecimal::add));
        var max=daily.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE).max(BigDecimal.ONE);
        List<Day> days=new ArrayList<>();for(var date=from;!date.isAfter(to);date=date.plusDays(1)) {var amount=daily.getOrDefault(date,BigDecimal.ZERO);days.add(new Day(date,amount,amount.multiply(BigDecimal.valueOf(100)).divide(max,0,java.math.RoundingMode.HALF_UP).intValue()));}
        List<PaymentTotal> payments=Arrays.stream(PaymentMethod.values()).map(method->{var rows=paid.stream().filter(i->i.getMethod()==method).toList();return new PaymentTotal(method,rows.size(),rows.stream().map(Invoice::getTotal).reduce(BigDecimal.ZERO,BigDecimal::add));}).toList();
        var branchRows=branches.findAll().stream().filter(b->branch==null||b.getId().equals(branch)).sorted(Comparator.comparing(Branch::getName)).map(b->{var rows=paid.stream().filter(i->i.getSession().getTable().getBranch().getId().equals(b.getId())).toList();return new BranchTotal(b.getName(),rows.size(),rows.stream().map(Invoice::getTotal).reduce(BigDecimal.ZERO,BigDecimal::add));}).toList();
        var counts=orders.statusCounts(branch,period.start(),period.end());
        long served=counts.stream().filter(c->c.getStatus()==OrderStatus.SERVED).mapToLong(FoodOrderRepository.StatusCount::getCount).sum();
        long cancelled=counts.stream().filter(c->c.getStatus()==OrderStatus.CANCELLED).mapToLong(FoodOrderRepository.StatusCount::getCount).sum();
        return new Summary(paid,total,average,served,cancelled,days,top(paid),payments,branchRows);
    }
}
