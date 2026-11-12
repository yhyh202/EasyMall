package com.easymall;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth=auth; }
    @PostMapping("/register") @Operation(summary="注册普通用户")
    public Api<Map<String,Object>> register(@Valid @RequestBody Requests.Credentials c) { return Api.ok(auth.register(c)); }
    @PostMapping("/login") @Operation(summary="登录并获得 12 小时 Bearer token 及签名用的 signKey")
    public Api<Map<String,Object>> login(@Valid @RequestBody Requests.Credentials c) { return Api.ok(auth.login(c)); }
    @GetMapping("/me") @Operation(summary="当前用户")
    public Api<SecurityConfig.User> me(@AuthenticationPrincipal SecurityConfig.User user) { return Api.ok(user); }
    @PostMapping("/logout") @Operation(summary="注销当前令牌")
    public Api<Void> logout(@RequestHeader("Authorization") String header) { auth.logout(header); return Api.ok(null); }
}
