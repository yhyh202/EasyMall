package com.easymall;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orders;
    public OrderController(OrderService orders) { this.orders=orders; }
    @PostMapping @Operation(summary="下单并扣减可售库存（支持幂等）")
    public Api<Map<String,Object>> create(@AuthenticationPrincipal SecurityConfig.User u,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Requests.Order o) { return Api.ok(orders.create(u.id(),o,key)); }
    @GetMapping @Operation(summary="当前用户订单分页")
    public Api<Api.Page<Map<String,Object>>> list(@AuthenticationPrincipal SecurityConfig.User u,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return Api.ok(orders.list(u.id(),false,status,page,size)); }
    @GetMapping("/{id}") @Operation(summary="订单详情与商品价格/收货地址快照")
    public Api<Map<String,Object>> detail(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id) { return Api.ok(orders.detail(u.id(),id,false)); }
    @PostMapping("/{id}/cancel") @Operation(summary="取消待支付订单并回补库存（可重复调用）")
    public Api<Map<String,Object>> cancel(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id) { return Api.ok(orders.cancel(u.id(),id)); }
    @PostMapping("/{id}/pay") @Operation(summary="模拟支付成功（支持幂等，不接真实支付网关）")
    public Api<Map<String,Object>> pay(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id,@RequestHeader("Idempotency-Key") String key) { return Api.ok(orders.pay(u.id(),id,key)); }
    @GetMapping("/{id}/payment") @Operation(summary="查询模拟支付记录")
    public Api<Map<String,Object>> payment(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id) { return Api.ok(orders.payment(u.id(),id)); }
    @PostMapping("/{id}/receive") @Operation(summary="确认收货（可重复调用）")
    public Api<Map<String,Object>> receive(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id) { return Api.ok(orders.receive(u.id(),id)); }
}
