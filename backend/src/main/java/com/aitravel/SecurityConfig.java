package com.aitravel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean HttpSessionSecurityContextRepository contextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean UserDetailsService userDetails(UserMapper users) {
        return username -> {
            var u = users.findByUsername(username);
            if (u == null) throw new UsernameNotFoundException("Unknown user");
            return User.withUsername(u.username).password(u.passwordHash).roles(u.role).build();
        };
    }
    @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper json, HttpSessionSecurityContextRepository contexts) throws Exception {
        http.csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .securityContext(c -> c.securityContextRepository(contexts))
            .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/api/meta", "/api/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().authenticated())
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> {
                res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); json.writeValue(res.getWriter(), ApiError.body("UNAUTHENTICATED", "请先登录"));
            }).accessDeniedHandler((req,res,ex) -> {
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8"); json.writeValue(res.getWriter(), ApiError.body("FORBIDDEN", "操作无权限或安全令牌已过期，请刷新页面"));
            }));
        return http.build();
    }
    @Bean Jackson2ObjectMapperBuilderCustomizer identifiers() {
        return b -> b.serializerByType(Long.class, ToStringSerializer.instance).serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
