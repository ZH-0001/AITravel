package com.aitravel;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final UserMapper users;
    private final PasswordEncoder encoder;
    private final UserDetailsService details;
    private final HttpSessionSecurityContextRepository contexts;
    private final TravelSettings settings;
    public AuthController(UserMapper users, PasswordEncoder encoder, UserDetailsService details, HttpSessionSecurityContextRepository contexts, TravelSettings settings) {
        this.users=users; this.encoder=encoder; this.details=details; this.contexts=contexts; this.settings=settings;
    }
    @GetMapping("/meta") Map<String,Object> meta() { return Map.of("mode", settings.mode(), "model", settings.model(), "searchProvider", settings.demo() ? "DEMO" : "Bocha"); }
    @GetMapping("/auth/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken(),"headerName",token.getHeaderName()); }
    @PostMapping("/auth/register") Map<String,Object> register(@RequestBody Map<String,String> form) {
        String name=value(form,"username").strip(), password=value(form,"password");
        if (!name.matches("[a-zA-Z0-9_]{3,30}") || password.length()<8 || password.getBytes(StandardCharsets.UTF_8).length>72) throw ApiError.bad("账号限 3～30 位字母、数字或下划线，密码至少 8 位且不超过 72 字节");
        var u = new UserAccount(); u.username=name; u.passwordHash=encoder.encode(password); u.nickname=name; u.role="USER";
        try { users.insert(u); } catch(DuplicateKeyException e) { throw new ApiError(409,"USERNAME_EXISTS","账号已存在"); }
        return publicUser(u);
    }
    @PostMapping("/auth/login") Map<String,Object> login(@RequestBody Map<String,String> form, HttpServletRequest req, HttpServletResponse res) {
        var u=users.findByUsername(value(form,"username").strip());
        String password=value(form,"password");
        if(password.getBytes(StandardCharsets.UTF_8).length>72 || u==null || !encoder.matches(password,u.passwordHash)) throw new ApiError(401,"INVALID_CREDENTIALS","账号或密码不正确");
        req.getSession(); req.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        var d=details.loadUserByUsername(u.username);
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(d,null,d.getAuthorities()));
        SecurityContextHolder.setContext(context); contexts.saveContext(context,req,res);
        return publicUser(u);
    }
    @PostMapping("/auth/logout") Map<String,Boolean> logout(HttpServletRequest req) {
        if(req.getSession(false)!=null) req.getSession(false).invalidate(); SecurityContextHolder.clearContext(); return Map.of("success",true);
    }
    @GetMapping("/users/me") Map<String,Object> me(Authentication auth) { return publicUser(account(auth)); }
    @PatchMapping("/users/me") Map<String,Object> update(Authentication auth,@RequestBody Map<String,String> form) {
        var u=account(auth); String nickname=value(form,"nickname").strip();
        if(nickname.isBlank()||nickname.length()>50) throw ApiError.bad("昵称限 1～50 字");
        u.nickname=nickname; users.updateById(u); return publicUser(u);
    }
    public UserAccount account(Authentication auth) { var u=users.findByUsername(auth.getName()); if(u==null) throw ApiError.notFound(); return u; }
    private String value(Map<String,String> form,String name) { return java.util.Objects.toString(form.get(name),""); }
    private Map<String,Object> publicUser(UserAccount u) { return Map.of("userId",u.userId,"username",u.username,"nickname",u.nickname,"role",u.role); }
}
