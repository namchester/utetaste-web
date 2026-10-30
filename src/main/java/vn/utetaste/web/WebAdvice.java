package vn.utetaste.web;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import vn.utetaste.security.Actor;
import vn.utetaste.service.BusinessException;
@ControllerAdvice
public class WebAdvice {
    @Value("${app.seed-demo}") boolean demo;
    @ModelAttribute void shared(@AuthenticationPrincipal Actor a,Model m,HttpServletRequest req){m.addAttribute("actor",a);m.addAttribute("currentPath",req.getRequestURI());m.addAttribute("demo",demo);}
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    Object malformed(Exception e,HttpServletRequest req,Model m,jakarta.servlet.http.HttpServletResponse response) {
        String msg="Dữ liệu yêu cầu không hợp lệ. Hãy kiểm tra kiểu dữ liệu và các trường bắt buộc.";
        if(req.getRequestURI().startsWith("/api/")) return ResponseEntity.badRequest().body(Map.of("message",msg));
        response.setStatus(400);m.addAttribute("message",msg);m.addAttribute("code",400);return "error";
    }
    @ExceptionHandler({BusinessException.class,NoSuchElementException.class,DataIntegrityViolationException.class,MethodArgumentNotValidException.class,MaxUploadSizeExceededException.class,AccessDeniedException.class})
    Object error(Exception e,HttpServletRequest req,Model m,jakarta.servlet.http.HttpServletResponse response) {
        int status=e instanceof AccessDeniedException?403:400;
        String msg=e instanceof BusinessException||e instanceof AccessDeniedException?e.getMessage():e instanceof DataIntegrityViolationException?"Dữ liệu bị trùng hoặc đang được sử dụng. Kiểm tra tên tài khoản, danh mục hoặc số bàn.":e instanceof MaxUploadSizeExceededException?"Ảnh vượt giới hạn 5 MB.":e instanceof MethodArgumentNotValidException?"Dữ liệu chưa hợp lệ. Kiểm tra các trường bắt buộc, số lượng và độ dài.":"Không tìm thấy dữ liệu yêu cầu.";
        if(req.getRequestURI().startsWith("/api/")) return ResponseEntity.status(status).body(Map.of("message",msg));
        response.setStatus(status);m.addAttribute("message",msg);m.addAttribute("code",status);return "error";
    }
}
