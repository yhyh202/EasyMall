package com.easymall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("demo")
class PurchaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Db db;
    @Autowired AuthService auth;
    @Autowired CatalogService catalog;
    @Autowired CustomerService customers;
    @Autowired OrderService orders;

    record Buyer(long id,String token,long address) {}
    String key() { return UUID.randomUUID().toString(); }
    JsonNode call(MockHttpServletRequestBuilder request,String token,Object body,int expected) throws Exception {
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(body!=null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        var result=mvc.perform(request).andExpect(status().is(expected)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
    Buyer buyer() {
        String username="u"+key().replace("-","");
        var c=new Requests.Credentials(username,"BuyerPass123!");
        long user=Db.number(auth.register(c),"id"); String token=(String)auth.login(c).get("token");
        long address=Db.number(customers.saveAddress(user,null,new Requests.Address("测试用户","13800138000","测试省市路 1 号",true)),"id");
        return new Buyer(user,token,address);
    }
    long admin() { return Db.number(db.one("SELECT id FROM mall_user WHERE username='admin'"),"id"); }
    String adminToken() { return (String)auth.login(new Requests.Credentials("admin","DemoAdmin123!")).get("token"); }
    long product(int stock) {
        long category=Db.number(catalog.categories().getFirst(),"id");
        long id=Db.number(catalog.saveProduct(null,new Requests.Product(category,"test-"+key(),"test",new BigDecimal("12.30"),true)),"id");
        if(stock>0) catalog.stock(admin(),id,new Requests.Stock(stock),key());
        return id;
    }
    Requests.Order order(Buyer b,long product,int quantity) { return new Requests.Order(b.address(),List.of(new Requests.Line(product,quantity)),true); }
    long stock(long product) { return Db.number(catalog.product(product,true),"stock"); }
    List<Object> concurrent(int count,java.util.function.IntFunction<Object> task) throws Exception {
        try(var pool=Executors.newFixedThreadPool(count)) {
            var gate=new CountDownLatch(1); var futures=new ArrayList<Future<Object>>();
            for(int i=0;i<count;i++) { final int index=i; futures.add(pool.submit(()->{gate.await(); return task.apply(index);})); }
            gate.countDown(); var results=new ArrayList<Object>();
            for(var future:futures) results.add(future.get(30,TimeUnit.SECONDS));
            return results;
        }
    }
    @Test void completePurchaseThroughHttp() throws Exception {
        var b=buyer(); long product=product(8);
        call(put("/api/cart/items"),b.token(),new Requests.Cart(product,2),200);
        String orderKey=key();
        var created=call(post("/api/orders").header("Idempotency-Key",orderKey),b.token(),order(b,product,2),200).get("data");
        long id=created.get("id").asLong();
        assertThat(created.get("total_amount").decimalValue()).isEqualByComparingTo("24.60");
        assertThat(stock(product)).isEqualTo(6);
        assertThat(call(get("/api/cart"),b.token(),null,200).get("data").size()).isZero();
        assertThat(call(post("/api/orders").header("Idempotency-Key",orderKey),b.token(),order(b,product,2),200).at("/data/id").asLong()).isEqualTo(id);
        String paymentKey=key();
        long payment=call(post("/api/orders/"+id+"/pay").header("Idempotency-Key",paymentKey),b.token(),null,200).at("/data/id").asLong();
        assertThat(call(post("/api/orders/"+id+"/pay").header("Idempotency-Key",paymentKey),b.token(),null,200).at("/data/id").asLong()).isEqualTo(payment);
        call(post("/api/orders/"+id+"/cancel"),b.token(),null,409);
        call(post("/api/admin/orders/"+id+"/ship"),adminToken(),new Requests.Ship("TRACK-123"),200);
        assertThat(call(post("/api/orders/"+id+"/receive"),b.token(),null,200).at("/data/status").asText()).isEqualTo("COMPLETED");
        call(post("/api/orders/"+id+"/receive"),b.token(),null,200);
        assertThat(stock(product)).isEqualTo(6);
    }
    @Test void concurrentIdempotencyCreatesOneOrderAndPayment() throws Exception {
        var b=buyer(); long product=product(10); String createKey=key();
        var results=concurrent(8,i->orders.create(b.id(),order(b,product,2),createKey));
        var ids=results.stream().map(o->Db.number((Map<String,Object>)o,"id")).distinct().toList();
        assertThat(ids).hasSize(1); assertThat(stock(product)).isEqualTo(8);
        String payKey=key(); var payments=concurrent(8,i->orders.pay(b.id(),ids.getFirst(),payKey));
        assertThat(payments.stream().map(o->Db.number((Map<String,Object>)o,"id")).distinct()).hasSize(1);
        assertThat(db.rows("SELECT * FROM stock_log WHERE product_id=? AND reason='RESERVE'",product)).hasSize(1);
    }
    @Test void concurrentBuyersCannotOversell() throws Exception {
        long product=product(3); var buyers=new ArrayList<Buyer>(); for(int i=0;i<10;i++) buyers.add(buyer());
        var results=concurrent(10,i->{ var b=buyers.get(i); try { orders.create(b.id(),order(b,product,1),key()); return "OK"; } catch(BusinessException e) { return e.code; } });
        assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(3);
        assertThat(results.stream().filter("CONFLICT"::equals).count()).isEqualTo(7);
        assertThat(stock(product)).isZero();
        assertThat(db.rows("SELECT * FROM stock_log WHERE product_id=? AND reason='RESERVE'",product)).hasSize(3);
    }
    @Test void concurrentCancelRestoresStockOnlyOnce() throws Exception {
        var b=buyer(); long product=product(5); long id=Db.number(orders.create(b.id(),order(b,product,3),key()),"id");
        concurrent(8,i->orders.cancel(b.id(),id));
        assertThat(stock(product)).isEqualTo(5);
        assertThat(db.rows("SELECT * FROM stock_log WHERE order_id=? AND reason='RELEASE'",id)).hasSize(1);
        assertThatThrownBy(()->orders.pay(b.id(),id,key())).isInstanceOf(BusinessException.class);
    }
    @Test void paymentCancelRaceHasOneConsistentOutcome() throws Exception {
        var b=buyer(); long product=product(5); long id=Db.number(orders.create(b.id(),order(b,product,2),key()),"id");
        var results=concurrent(2,i->{ try { return i==0?orders.pay(b.id(),id,key()):orders.cancel(b.id(),id); } catch(BusinessException e) { return e.code; } });
        assertThat(results.stream().filter("CONFLICT"::equals).count()).isEqualTo(1);
        String state=(String)orders.detail(b.id(),id,false).get("status");
        assertThat(state).isIn("PAID","CANCELLED");
        assertThat(stock(product)).isEqualTo(state.equals("PAID")?3:5);
        assertThat(db.rows("SELECT * FROM payment WHERE order_id=?",id)).hasSize(state.equals("PAID")?1:0);
    }
    @Test void failedMultiItemOrderLeavesNoPartialWrites() {
        var b=buyer(); long available=product(5),empty=product(0);
        customers.putCart(b.id(),new Requests.Cart(available,2));
        var request=new Requests.Order(b.address(),List.of(new Requests.Line(available,2),new Requests.Line(empty,1)),true);
        assertThatThrownBy(()->orders.create(b.id(),request,key())).isInstanceOf(BusinessException.class);
        assertThat(stock(available)).isEqualTo(5);
        assertThat(orders.list(b.id(),false,null,1,20).total()).isZero();
        assertThat(customers.cart(b.id())).hasSize(1);
        assertThat(db.rows("SELECT * FROM stock_log WHERE product_id=? AND reason='RESERVE'",available)).isEmpty();
    }
    @Test void ownershipRolesAndTokensAreEnforced() throws Exception {
        var a=buyer(); var b=buyer(); long product=product(4);
        long id=Db.number(orders.create(a.id(),order(a,product,1),key()),"id");
        call(get("/api/orders/"+id),null,null,401);
        call(get("/api/orders/"+id),b.token(),null,404);
        call(post("/api/orders/"+id+"/pay").header("Idempotency-Key",key()),b.token(),null,404);
        call(delete("/api/addresses/"+a.address()),b.token(),null,404);
        call(get("/api/admin/orders"),b.token(),null,403);
        call(post("/api/auth/logout"),a.token(),null,200);
        call(get("/api/auth/me"),a.token(),null,401);
        db.update("UPDATE auth_token SET expires_at=? WHERE token_hash=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(30)),AuthService.hash(b.token()));
        call(get("/api/auth/me"),b.token(),null,401);
    }
    @Test void validationAndIdempotencyPayloadConflicts() throws Exception {
        var b=buyer(); long product=product(5);
        call(get("/api/products?size=101"),null,null,400);
        call(post("/api/orders").header("Idempotency-Key",key()),b.token(),Map.of("addressId",b.address(),"items",Arrays.asList((Object)null)),400);
        call(post("/api/orders"),b.token(),order(b,product,1),400);
        call(post("/api/orders").header("Idempotency-Key","bad"),b.token(),order(b,product,1),400);
        call(put("/api/cart/items"),b.token(),new Requests.Cart(product,0),400);
        String createKey=key(); orders.create(b.id(),order(b,product,1),createKey);
        call(post("/api/orders").header("Idempotency-Key",createKey),b.token(),order(b,product,2),409);
        assertThat(stock(product)).isEqualTo(4);
    }
    @Test void stockAdjustmentIsIdempotentAndNeverNegative() throws Exception {
        long product=product(2); String stockKey=key();
        concurrent(6,i->catalog.stock(admin(),product,new Requests.Stock(3),stockKey));
        assertThat(stock(product)).isEqualTo(5);
        assertThatThrownBy(()->catalog.stock(admin(),product,new Requests.Stock(4),stockKey)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->catalog.stock(admin(),product,new Requests.Stock(-6),key())).isInstanceOf(BusinessException.class);
        assertThat(stock(product)).isEqualTo(5);
    }
    @Test void orderKeepsPriceAndAddressSnapshots() {
        var b=buyer(); long product=product(5); long id=Db.number(orders.create(b.id(),order(b,product,1),key()),"id");
        long category=Db.number(catalog.product(product,true),"category_id");
        catalog.saveProduct(product,new Requests.Product(category,"new name","changed",new BigDecimal("99.99"),false));
        customers.deleteAddress(b.id(),b.address());
        var detail=orders.detail(b.id(),id,false);
        assertThat((BigDecimal)detail.get("total_amount")).isEqualByComparingTo("12.30");
        assertThat(detail.get("address_detail")).isEqualTo("测试省市路 1 号");
        orders.cancel(b.id(),id); assertThat(stock(product)).isEqualTo(5);
    }
    @Test void documentationAndHealthAreAvailable() throws Exception {
        call(get("/actuator/health"),null,null,200);
        var spec=call(get("/v3/api-docs"),null,null,200);
        assertThat(spec.at("/paths/~1api~1orders/post").isMissingNode()).isFalse();
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
    @Test void registrationNormalizesUsernameAndRejectsDuplicates() throws Exception {
        String name="User"+key().replace("-","");
        var c=new Requests.Credentials(name,"BuyerPass123!");
        assertThat(call(post("/api/auth/register"),null,c,200).at("/data/username").asText()).isEqualTo(name.toLowerCase(Locale.ROOT));
        call(post("/api/auth/register"),null,c,409);
        call(post("/api/auth/login"),null,new Requests.Credentials(name,"WrongPass123!"),401);
        call(post("/api/auth/register"),null,new Requests.Credentials("bad","短密码不符合要求"),400);
    }
}
