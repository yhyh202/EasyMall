package com.easymall;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/admin") @PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final CatalogService catalog;
    private final OrderService orders;
    public AdminController(CatalogService catalog,OrderService orders) { this.catalog=catalog; this.orders=orders; }
    @PostMapping("/categories") @Operation(summary="新增分类")
    public Api<Map<String,Object>> category(@Valid @RequestBody Requests.Category c) { return Api.ok(catalog.saveCategory(null,c)); }
    @PutMapping("/categories/{id}") @Operation(summary="修改分类")
    public Api<Map<String,Object>> category(@PathVariable long id,@Valid @RequestBody Requests.Category c) { return Api.ok(catalog.saveCategory(id,c)); }
    @DeleteMapping("/categories/{id}") @Operation(summary="删除无商品引用的分类")
    public Api<Void> deleteCategory(@PathVariable long id) { catalog.deleteCategory(id); return Api.ok(null); }
    @GetMapping("/products") @Operation(summary="管理端商品分页，含下架商品")
    public Api<Api.Page<Map<String,Object>>> products(@RequestParam(required=false) String keyword,@RequestParam(required=false) Long categoryId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return Api.ok(catalog.products(keyword,categoryId,true,page,size)); }
    @GetMapping("/products/{id}") @Operation(summary="管理端商品详情")
    public Api<Map<String,Object>> product(@PathVariable long id) { return Api.ok(catalog.product(id,true)); }
    @PostMapping("/products") @Operation(summary="新增商品，初始库存为零，随后用库存接口入库")
    public Api<Map<String,Object>> product(@Valid @RequestBody Requests.Product p) { return Api.ok(catalog.saveProduct(null,p)); }
    @PutMapping("/products/{id}") @Operation(summary="修改商品信息和上架状态；不直接覆盖库存")
    public Api<Map<String,Object>> product(@PathVariable long id,@Valid @RequestBody Requests.Product p) { return Api.ok(catalog.saveProduct(id,p)); }
    @DeleteMapping("/products/{id}") @Operation(summary="逻辑下架商品，保留订单引用")
    public Api<Void> deactivate(@PathVariable long id) { catalog.deactivate(id); return Api.ok(null); }
    @PostMapping("/products/{id}/stock") @Operation(summary="增减库存并记录流水（支持幂等）")
    public Api<Map<String,Object>> stock(@AuthenticationPrincipal SecurityConfig.User u,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Requests.Stock s) { return Api.ok(catalog.stock(u.id(),id,s,key)); }
    @GetMapping("/products/{id}/stock-logs") @Operation(summary="库存流水分页")
    public Api<Api.Page<Map<String,Object>>> stockLogs(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return Api.ok(catalog.stockLogs(id,page,size)); }
    @GetMapping("/orders") @Operation(summary="全站订单分页")
    public Api<Api.Page<Map<String,Object>>> orders(@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return Api.ok(orders.list(0,true,status,page,size)); }
    @GetMapping("/orders/{id}") @Operation(summary="管理端订单详情")
    public Api<Map<String,Object>> order(@PathVariable long id) { return Api.ok(orders.detail(0,id,true)); }
    @PostMapping("/orders/{id}/ship") @Operation(summary="发货，重复相同物流单号不会重复变更")
    public Api<Map<String,Object>> ship(@PathVariable long id,@Valid @RequestBody Requests.Ship s) { return Api.ok(orders.ship(id,s.shippingNo())); }
}
