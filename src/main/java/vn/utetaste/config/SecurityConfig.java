package vn.utetaste.config;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import vn.utetaste.security.JwtCookieFilter;
@Configuration @RequiredArgsConstructor
public class SecurityConfig {
    private final JwtCookieFilter jwtFilter;
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<JwtCookieFilter> jwtServletRegistration() {
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(jwtFilter);registration.setEnabled(false);return registration;
    }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // JWT revalidation happens on every request, including images and CSS.
            // It is not a new session login: preserve the page's CSRF cookie.
            .csrf(c->c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
            .authorizeHttpRequests(a->a
                .requestMatchers("/register","/verify-email","/verify-email/resend","/forgot-password","/forgot-password/verify","/reset-password").permitAll()
                .requestMatchers("/","/login","/logout","/t/**","/css/**","/js/**","/images/**","/fonts/**","/webjars/**","/error","/favicon.ico").permitAll()
                .requestMatchers("/guest/**","/api/guest/**","/ws/guest").hasRole("GUEST")
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/staff/menu/**","/staff/reports/**").hasAnyRole("ADMIN","MANAGER")
                .requestMatchers("/staff/tables/**","/staff/sessions/**","/staff/calls/**","/staff/receipt/**").hasAnyRole("ADMIN","MANAGER","WAITER")
                .requestMatchers("/account/profile","/account/security","/account/orders","/account/orders/**","/account/favorites","/account/favorites/**","/account/points","/api/member/**").hasRole("USER")
                .requestMatchers("/account","/account/**").hasAnyRole("ADMIN","MANAGER","WAITER","CHEF","USER")
                .requestMatchers("/staff/**","/api/staff/**","/ws/staff").hasAnyRole("ADMIN","MANAGER","WAITER","CHEF")
                .anyRequest().denyAll())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{
                if(req.getRequestURI().startsWith("/api/")||req.getRequestURI().startsWith("/ws/")) { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"message\":\"Phiên đăng nhập hết hạn. Hãy đăng nhập hoặc quét QR lại.\"}"); }
                else res.sendRedirect("/login?expired");
            }))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; font-src 'self' data:; img-src 'self' https://res.cloudinary.com data: blob:; connect-src 'self' ws: wss:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
            .addFilterBefore(jwtFilter,UsernamePasswordAuthenticationFilter.class)
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable()).build();
    }
}
