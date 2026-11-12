package com.easymall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    public record User(long id,String username,String role) {}
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("EasyMall REST API").version("1.0.0").description("商品/分类 GET 及注册登录公开；其他接口使用 Bearer token。"
                +"携带 token 的请求必须用登录返回的 signKey 签名（X-Timestamp、X-Nonce、X-Signature，规则见 docs/API.md）；本页面登录后会自动授权并签名。"
                +"下单、支付、调库存需要 Idempotency-Key。数据库响应字段使用 snake_case，请求字段使用 camelCase。"))
            .components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                .description("登录得到的 token。Swagger 页面会用同一次登录的 signKey 自动签名")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
    @Bean NonceStore nonceStore(@Value("${app.signature.nonce-store:memory}") String type,
                                @Value("${app.signature.memory-nonce-capacity:1000000}") int capacity,
                                ObjectProvider<StringRedisTemplate> redis) {
        return switch(type) {
            case "memory" -> new NonceStore.Memory(capacity);
            case "redis" -> new NonceStore.Redis(redis.getObject());
            default -> throw new IllegalArgumentException("app.signature.nonce-store 只能是 memory 或 redis");
        };
    }
    @Bean SecurityFilterChain chain(HttpSecurity http,Db db,ObjectMapper mapper,RequestSignature signature) throws Exception {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                String header=req.getHeader("Authorization");
                if(header!=null && header.startsWith("Bearer ") && header.length()<=200) {
                    String tokenHash=AuthService.hash(header.substring(7));
                    try {
                        // Signature first: it needs no database access, so forged or unsigned requests are rejected cheaply.
                        RequestSignature.Signed signed=null;
                        if(signature.enabled()) { signed=signature.verify(req,tokenHash); req=signed.request(); }
                        var rows=db.rows("SELECT u.id,u.username,u.role FROM auth_token t JOIN mall_user u ON t.user_id=u.id WHERE t.token_hash=? AND t.expires_at>CURRENT_TIMESTAMP",tokenHash);
                        if(!rows.isEmpty()) {
                            if(signed!=null) signature.claimNonce(tokenHash,signed);
                            var row=rows.getFirst(); var user=new User(Db.number(row,"id"),(String)row.get("username"),(String)row.get("role"));
                            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.role()))));
                        }
                    } catch(BusinessException e) { write(mapper,res,e.status,e.code,e.getMessage()); return; }
                }
                chain.doFilter(req,res);
            }
        };
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/api/auth/register","/api/auth/login","/swagger-ui.html","/swagger-ui/**","/v3/api-docs/**","/actuator/health","/error").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/products","/api/products/*","/api/categories").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->write(mapper,res,401,"UNAUTHORIZED","请登录或更新令牌"))
                .accessDeniedHandler((req,res,ex)->write(mapper,res,403,"FORBIDDEN","需要管理员权限")))
            .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class).build();
    }
    static void write(ObjectMapper mapper,HttpServletResponse res,int status,String code,String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); mapper.writeValue(res.getOutputStream(),Api.error(code,message));
    }
}
