package com.easymall;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api")
public class StoreController {
    private final CatalogService catalog;
    public StoreController(CatalogService catalog) { this.catalog=catalog; }
    @GetMapping("/categories") @Operation(summary="分类列表")
    public Api<List<Map<String,Object>>> categories() { return Api.ok(catalog.categories()); }
    @GetMapping("/products") @Operation(summary="在售商品分页、关键词和分类筛选")
    public Api<Api.Page<Map<String,Object>>> products(@RequestParam(required=false) String keyword,@RequestParam(required=false) Long categoryId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return Api.ok(catalog.products(keyword,categoryId,false,page,size)); }
    @GetMapping("/products/{id}") @Operation(summary="在售商品详情")
    public Api<Map<String,Object>> product(@PathVariable long id) { return Api.ok(catalog.product(id,false)); }
}
