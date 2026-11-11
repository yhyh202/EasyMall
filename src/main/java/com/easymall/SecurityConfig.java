package com.easymall;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
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
        return new OpenAPI().info(new Info().title("EasyMall REST API").version("1.0.0").description("商品/分类 GET 及注册登录公开；其他接口使用 Bearer token。下单、支付、调库存需要 Idempotency-Key。数据库响应字段使用 snake_case，请求字段使用 camelCase。"))
            .components(new Components().addSecuritySchemes("bearerAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
    @Bean SecurityFilterChain chain(HttpSecurity http,Db db,ObjectMapper mapper) throws Exception {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                String header=req.getHeader("Authorization");
                if(header!=null && header.startsWith("Bearer ") && header.length()<=200) {
                    var rows=db.rows("SELECT u.id,u.username,u.role FROM auth_token t JOIN mall_user u ON t.user_id=u.id WHERE t.token_hash=? AND t.expires_at>CURRENT_TIMESTAMP",AuthService.hash(header.substring(7)));
                    if(!rows.isEmpty()) {
                        var row=rows.getFirst(); var user=new User(Db.number(row,"id"),(String)row.get("username"),(String)row.get("role"));
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.role()))));
                    }
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
    private static void write(ObjectMapper mapper,HttpServletResponse res,int status,String code,String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8"); mapper.writeValue(res.getOutputStream(),Api.error(code,message));
    }
}
