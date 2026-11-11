package com.easymall;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class CustomerController {
    private final CustomerService customers;
    public CustomerController(CustomerService customers) { this.customers=customers; }
    @GetMapping("/addresses") @Operation(summary="当前用户地址列表")
    public Api<List<Map<String,Object>>> addresses(@AuthenticationPrincipal SecurityConfig.User u) { return Api.ok(customers.addresses(u.id())); }
    @PostMapping("/addresses") @Operation(summary="添加地址")
    public Api<Map<String,Object>> address(@AuthenticationPrincipal SecurityConfig.User u,@Valid @RequestBody Requests.Address a) { return Api.ok(customers.saveAddress(u.id(),null,a)); }
    @PutMapping("/addresses/{id}") @Operation(summary="修改地址或设置默认地址")
    public Api<Map<String,Object>> address(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id,@Valid @RequestBody Requests.Address a) { return Api.ok(customers.saveAddress(u.id(),id,a)); }
    @DeleteMapping("/addresses/{id}") @Operation(summary="删除地址")
    public Api<Void> deleteAddress(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id) { customers.deleteAddress(u.id(),id); return Api.ok(null); }
    @GetMapping("/cart") @Operation(summary="购物车列表（不预占库存）")
    public Api<List<Map<String,Object>>> cart(@AuthenticationPrincipal SecurityConfig.User u) { return Api.ok(customers.cart(u.id())); }
    @PutMapping("/cart/items") @Operation(summary="按商品设置购物车绝对数量，可重复调用")
    public Api<List<Map<String,Object>>> cart(@AuthenticationPrincipal SecurityConfig.User u,@Valid @RequestBody Requests.Cart c) { return Api.ok(customers.putCart(u.id(),c)); }
    @PatchMapping("/cart/items/{id}") @Operation(summary="修改购物车行数量")
    public Api<List<Map<String,Object>>> quantity(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id,@Valid @RequestBody Requests.Quantity q) { return Api.ok(customers.quantity(u.id(),id,q.quantity())); }
    @DeleteMapping("/cart/items/{id}") @Operation(summary="删除购物车行")
    public Api<Void> deleteCart(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id) { customers.deleteCart(u.id(),id); return Api.ok(null); }
    @DeleteMapping("/cart") @Operation(summary="清空购物车")
    public Api<Void> clearCart(@AuthenticationPrincipal SecurityConfig.User u) { customers.deleteCart(u.id(),null); return Api.ok(null); }
}
