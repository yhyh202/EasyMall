# API 与数据库设计

## 通用约定

根地址 `http://localhost:8080`。请求 JSON 为 camelCase；来自数据库的响应字段为 snake_case。所有 ID 为正整数；时间以 ISO 8601 输出。业务金额使用 DECIMAL，JSON 为数字；支付金额只从订单读取。

公开：注册、登录、商品 GET、分类 GET。用户接口使用 `Authorization: Bearer <token>`；管理接口同样使用 token，且角色必须是 ADMIN。**携带 token 的请求必须签名**，规则见下文“请求签名”。

成功 HTTP 200（创建和删除也为 200）：

```json
{"code":"OK","message":"success","data":{"id":1},"timestamp":"2026-11-11T08:00:00Z"}
```

错误结构相同，`data=null`。400 `VALIDATION_ERROR`、401 `UNAUTHORIZED/INVALID_CREDENTIALS/SIGNATURE_MISSING/SIGNATURE_EXPIRED/SIGNATURE_INVALID`、403 `FORBIDDEN`、404 `NOT_FOUND`、405 `METHOD_NOT_ALLOWED`、409 `CONFLICT/RETRYABLE_CONFLICT/NONCE_REPLAYED`、413 `PAYLOAD_TOO_LARGE`、500 `INTERNAL_ERROR`、503 `NONCE_STORE_UNAVAILABLE`。校验失败不会返回数据库错误或堆栈。针对死锁/锁等待导致的 409，可短暂退避并使用原幂等键重试；业务冲突不应盲目重试。

分页：`page=1`、`size=20`，size 范围 1..100，page 范围 1..1000000；按 id 倒序。响应 `data={"items":[],"total":0,"page":1,"size":20}`。

## 请求对象

| 对象 | 字段与约束 |
|---|---|
| Credentials | username：3–50 位字母数字下划线，存储为小写；password：8–64 位可打印 ASCII，不含空格 |
| Category | name：非空，最长 80 |
| Product | categoryId：正整数且分类存在；name：非空≤150；description：必填，可空串≤2000；price：0.01..99999999.99，最多 2 位小数；active：必填布尔值 |
| Address | recipient：非空≤80；phone：5–30 位，限数字、空格、+、-；detail：非空≤500；isDefault：布尔值，省略为 false |
| Cart | productId：正整数且在售；quantity：1..999，表示绝对数量，不是增量 |
| Quantity | quantity：1..999 |
| Order | addressId：当前用户地址；items：1..50 个 `{productId,quantity}`，商品不重复且 quantity 1..999；clearCart：布尔值，省略 false |
| Stock | delta：-1000000..1000000 的非零整数；调整后库存 0..1000000000 |
| Ship | shippingNo：非空≤100 |

## 完整业务接口（36 个方法/路径组合）

“用户”为任意已登录用户；管理员也可作为买家使用用户接口。参数无特别说明时均为路径 id 或 JSON 请求体。

| 方法 | 路径 | 权限 | 参数 | data 响应 |
|---|---|---|---|---|
| POST | /api/auth/register | 公开 | Credentials | User |
| POST | /api/auth/login | 公开 | Credentials | `{token,tokenType,signKey,expiresAt,user}` |
| GET | /api/auth/me | 用户 | 无 | `{id,username,role}` |
| POST | /api/auth/logout | 用户 | 无 | null，注销当前 token |
| GET | /api/categories | 公开 | 无 | Category[] |
| GET | /api/products | 公开 | keyword≤100、categoryId、page、size | Page<Product>，仅在售 |
| GET | /api/products/{id} | 公开 | id | Product，仅在售 |
| GET | /api/addresses | 用户 | 无 | Address[]，默认地址优先 |
| POST | /api/addresses | 用户 | Address | Address |
| PUT | /api/addresses/{id} | 用户 | Address | Address |
| DELETE | /api/addresses/{id} | 用户 | id | null |
| GET | /api/cart | 用户 | 无 | CartItem[] |
| PUT | /api/cart/items | 用户 | Cart | 更新后的 CartItem[] |
| PATCH | /api/cart/items/{id} | 用户 | Quantity；id 是购物车行 id | CartItem[] |
| DELETE | /api/cart/items/{id} | 用户 | 购物车行 id | null |
| DELETE | /api/cart | 用户 | 无 | null |
| POST | /api/orders | 用户 | Order + Idempotency-Key | OrderDetail |
| GET | /api/orders | 用户 | status、page、size | Page<OrderSummary> |
| GET | /api/orders/{id} | 用户 | id | OrderDetail |
| POST | /api/orders/{id}/cancel | 用户 | id，无请求体 | OrderDetail |
| POST | /api/orders/{id}/pay | 用户 | id + Idempotency-Key，无请求体 | Payment |
| GET | /api/orders/{id}/payment | 用户 | id | Payment，未支付返回 404 |
| POST | /api/orders/{id}/receive | 用户 | id，无请求体 | OrderDetail |
| POST | /api/admin/categories | 管理员 | Category | Category |
| PUT | /api/admin/categories/{id} | 管理员 | Category | Category |
| DELETE | /api/admin/categories/{id} | 管理员 | id | null，有商品引用返回 409 |
| GET | /api/admin/products | 管理员 | keyword、categoryId、page、size | Page<Product>，含下架 |
| GET | /api/admin/products/{id} | 管理员 | id | Product，含下架 |
| POST | /api/admin/products | 管理员 | Product | Product，库存初始 0 |
| PUT | /api/admin/products/{id} | 管理员 | Product | Product，不修改库存 |
| DELETE | /api/admin/products/{id} | 管理员 | id | null，实际执行下架 |
| POST | /api/admin/products/{id}/stock | 管理员 | Stock + Idempotency-Key | StockLog，返回已执行的流水 |
| GET | /api/admin/products/{id}/stock-logs | 管理员 | id、page、size | Page<StockLog> |
| GET | /api/admin/orders | 管理员 | status、page、size | Page<OrderSummary> |
| GET | /api/admin/orders/{id} | 管理员 | id | OrderDetail |
| POST | /api/admin/orders/{id}/ship | 管理员 | Ship | OrderDetail |

另有标准工具接口：`GET /actuator/health`、`GET /v3/api-docs`、Swagger UI。这些接口不使用业务响应封装。

## 请求签名

**范围**：凡是带 `Authorization: Bearer <token>` 的请求都必须签名，包括本来公开的商品、分类接口；不带 token 的公开请求不需要签名。签名用来防止请求被篡改和重放，也让单独泄露的 token 无法直接使用。它不加密内容，生产环境仍需 HTTPS。

**签名密钥**：登录响应除 `token` 外还返回 `signKey`，两者属于同一次登录。服务端不存储 `signKey`，每次请求按 `Base64url(HMAC-SHA256(主密钥, "easymall-sign-key:v1:" + SHA256hex(token)))` 重新算出，所以注销或过期后它随 token 一起失效。HMAC 的密钥是 `signKey` 字符串的 UTF-8 字节。

**请求头**

| Header | 格式 |
|---|---|
| `X-Timestamp` | Unix 毫秒时间戳，与服务器时间相差不超过 300 秒 |
| `X-Nonce` | 16..64 位 `[A-Za-z0-9-]`，每个请求不同，建议用 UUID |
| `X-Signature` | `Base64(HMAC-SHA256(signKey, 待签名串))`，标准 Base64，带 `=` 填充 |

**待签名串**：6 行，用 `\n` 连接，末尾没有换行。

1. HTTP 方法，大写。
2. 路径：不含 query，按实际发送的原样，不做解码。
3. 规范化 query：每个参数先 URL 解码（`+` 视为空格），再按 RFC 3986 编码（只保留 `A-Z a-z 0-9 - . _ ~`，空格为 `%20`），然后按编码后的 key 排序，key 相同再按 value 排序，用 `&` 连接成 `k=v`。没有值的参数写成 `k=`，没有 query 时为空行。
4. `X-Timestamp` 的值。
5. `X-Nonce` 的值。
6. 请求体原始字节的 SHA-256 小写 hex。没有请求体时为空串的哈希 `e3b0c442…b855`。不要重新序列化 JSON，必须对实际发送的字节做哈希。

**示例**（[RequestSignatureTest](../src/test/java/com/easymall/RequestSignatureTest.java) 用同一组数据校验，可用于对照自测）

```text
signKey   = Ver7QLhO--GJ1ab3CvMofdHM8jQqZnE5jIbQuDsQAYA
请求       = POST /api/orders?size=20&keyword=%E6%89%8B%E6%9C%BA+%E5%A3%B3&page=1&a=*~
请求体     = {"addressId":1,"items":[{"productId":2,"quantity":1}],"clearCart":false}
X-Timestamp = 1731400000000
X-Nonce     = 3f9c2a7e-1b4d-4c8e-9a51-6d2e8f0b7c33

待签名串：
POST
/api/orders
a=%2A~&keyword=%E6%89%8B%E6%9C%BA%20%E5%A3%B3&page=1&size=20
1731400000000
3f9c2a7e-1b4d-4c8e-9a51-6d2e8f0b7c33
c781224df34eebf5d8ca5418bb8c66eea80e37a07519542e82b4b6eded67f8e2

X-Signature = 8DkZP0eshi5HRcfSutrAudOeKFe/KLMO9IWcuqFoxJw=
```

**服务端校验顺序**：请求头格式 → 时间戳窗口 → 读取请求体（上限 64KB）→ 重算签名并做恒定时间比较 → 查询 token → 记录 nonce。签名校验不需要查库，伪造或未签名的请求在查库之前就被拒绝。nonce 只在 token 有效且签名正确后才记录，所以错误请求不会占用 nonce。nonce 按 token 隔离，保存到 `X-Timestamp + 300 秒`，之后同样的请求会因时间戳过期被拒绝。

| 情况 | HTTP | code |
|---|---|---|
| 带 token 但缺少签名头，或格式不对 | 401 | `SIGNATURE_MISSING` |
| 时间戳超出 ±300 秒 | 401 | `SIGNATURE_EXPIRED` |
| 签名不匹配（方法、路径、query、请求体被改，或用错 signKey） | 401 | `SIGNATURE_INVALID` |
| 签名正确但 token 已注销或过期 | 401 | `UNAUTHORIZED` |
| 同一 token 重复使用 nonce | 409 | `NONCE_REPLAYED` |
| 请求体超过 64KB | 413 | `PAYLOAD_TOO_LARGE` |
| nonce 存储（Redis 模式）不可用 | 503 | `NONCE_STORE_UNAVAILABLE` |

**和幂等键的分工**：签名防止的是篡改和重放；`Idempotency-Key` 防止的是业务上的重复提交。客户端因超时重试下单或支付时，应该重新签名（新的时间戳和 nonce），但沿用原来的 `Idempotency-Key`，这样只会生成一笔订单或支付。

**配置**（`app.signature.*`）

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| enabled | `SIGNATURE_ENABLED` | true | 设为 false 时只校验 token，登录仍返回 signKey |
| master-key | `SIGN_MASTER_KEY` | 空 | 至少 32 个字符。为空时进程启动时随机生成，重启后需要重新登录，多实例之间也不能共用 token |
| max-skew | | 300s | 时间戳允许的偏差，也是 nonce 的保留时间 |
| max-body-size | | 64KB | 签名请求的请求体上限 |
| nonce-store | `SIGN_NONCE_STORE` | memory | memory 适用于单实例；redis 供多实例共享。Redis 出错时拒绝请求（503），不会跳过校验 |
| memory-nonce-capacity | | 1000000 | 内存模式下最多保存的 nonce 数，满了返回 503 |

**客户端实现**：[Swagger 注入脚本](../src/main/resources/swagger/request-signing.js)（浏览器 Web Crypto）、[benchmark.cjs](../scripts/benchmark.cjs)（Node）、[prepare-load-data.ps1](../scripts/prepare-load-data.ps1)（PowerShell）、[purchase.jmx](purchase.jmx)（JMeter Groovy）、Postman 集合级 Pre-request Script、[测试用 SignedRequests](../src/test/java/com/easymall/SignedRequests.java)（Java）。

## 响应对象字段

| 类型 | 字段 |
|---|---|
| User | id, username, role, created_at（register）；登录 user 不含 created_at |
| Category | id, name |
| Product | id, category_id, name, description, price, stock, active |
| Address | id, user_id, recipient, phone, detail, is_default |
| CartItem | id, product_id, quantity, name, price, stock, active |
| OrderSummary | id, user_id, request_key, status, total_amount, recipient, phone, address_detail, created_at, paid_at（可 null）, shipping_no（可 null） |
| OrderDetail | OrderSummary 所有字段 + items: OrderItem[] |
| OrderItem | id, order_id, product_id, product_name, unit_price, quantity |
| Payment | id, order_id, user_id, request_key, amount, status（SUCCESS）, created_at |
| StockLog | id, product_id, order_id（可 null）, actor_id, delta, reason, request_key（可 null）, created_at |

`stock` 是当前可售库存，下单即减少；支付不会再次扣库存。`StockLog.reason` 为 SEED（样例初始入库）、ADJUST（管理员增减）、RESERVE（下单扣减）、RELEASE（取消回补）。

## 幂等和事务边界

`Idempotency-Key` 必须为 8..64 位 `[A-Za-z0-9._:-]`。下单唯一键 `(user_id,request_key)`；支付分别限制每订单一条支付、每用户每键一条支付；调库存唯一键 `(actor_id,request_key)`。这三类键的命名空间互不干扰。

- 下单重放返回相同订单 id 和**当前**订单状态，不保证字节级原始响应。地址 id、排序后的商品数量、clearCart 参与请求摘要；更换内容必须更换键。同一用户串行锁保证同时提交时只扣一次。
- 同订单、同支付键重复支付返回原支付记录；不同订单复用支付键或已支付后更换键均返回 409。
- 库存调整重试返回原流水；更换商品或 delta 复用同键返回 409。
- 取消、确认收货和相同物流单号发货依据状态天然幂等。取消与支付竞争时仅一方成功，所有变更一起提交或回滚。
- 下单按商品 ID 升序行锁，检查上下架、可售库存和价格，建立订单快照，扣库存，写流水，按需更新购物车。
- 库存失败或其他事务异常会整体回滚订单、明细、流水和购物车更新。
- 地址默认标记通过用户行锁维护；可以没有默认地址，至多一个。订单不能使用其他用户地址。
- 单用户串行化有意降低复杂度；同一账号的高并发吞吐会受限。无自动关单，未支付订单需要取消才能释放库存。

## 数据表（10 张业务表）

| 表 | 用途与关系 |
|---|---|
| mall_user | 用户、BCrypt 摘要、USER/ADMIN 角色；username 唯一 |
| auth_token | token 摘要、用户、到期时间；不保存原 token |
| category | 商品分类，名称唯一 |
| product | 所属分类、价格、可售库存、上架状态 |
| address | 所属用户、收货信息、默认标记 |
| cart_item | 用户与商品唯一组合、数量 |
| mall_order | 用户、状态、金额、地址快照、幂等键与请求摘要 |
| order_item | 订单、商品、名称与成交价格快照、数量 |
| payment | 订单唯一支付记录，成功状态、金额、幂等键 |
| stock_log | 商品、可选订单、操作者、数量变化和原因 |

外键和索引详见 `src/main/resources/db/migration/V1__schema.sql`；另有 Flyway 自动创建的迁移历史表。默认 MySQL 8 InnoDB 事务及行锁。没有缓存层或外部消息依赖。
