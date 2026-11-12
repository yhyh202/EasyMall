# EasyMall 轻量级电商订单管理系统

EasyMall 是一个 Java 21 + Spring Boot 3.5.7 的轻量级电商订单管理后端，覆盖商品管理、购物车、下单、模拟支付、发货和确认收货。采用 Maven 单体架构，提供 36 个业务 REST 接口，适合学习业务建模、验证事务和并发正确性，以及进行 HTTP 压测。没有前端页面；Swagger 可直接调试。

## 技术栈与中间件

| 技术 | 项目中的用途 |
|---|---|
| Java 21、Maven | 开发语言、依赖管理、测试和可执行 JAR 打包 |
| Spring Boot 3.5.7、内嵌 Tomcat | 应用启动、自动配置、HTTP 服务 |
| Spring MVC、Jackson、Jakarta Bean Validation | REST 路由、JSON 转换、请求参数校验 |
| Spring Security、BCrypt | 身份认证、管理员授权、密码加密 |
| 随机 Bearer Token、SHA-256、HMAC-SHA256 | 登录凭证、令牌摘要存储、请求签名与防篡改 |
| Spring JDBC / JdbcTemplate、HikariCP | 参数化 SQL、数据库连接池；没有使用 MyBatis 或 JPA |
| MySQL 8 | 用户、商品、订单、支付与库存流水的持久化存储 |
| H2 | demo 模式及默认集成测试的内存数据库 |
| Flyway | 自动执行和记录数据库结构迁移 |
| Spring 事务、数据库行锁、唯一约束 | 原子提交、失败回滚、并发库存控制和幂等保护 |
| Redis、Spring Data Redis | 可选的商品搜索总数缓存、可选的共享 nonce 防重放存储 |
| Springdoc OpenAPI 2.8.14、Swagger UI | 接口文档、在线调试与自动请求签名 |
| Spring Boot Actuator、SLF4J / Logback | 健康检查、运行日志；隔离压测环境额外开放 metrics |
| JUnit 5、Spring Boot Test、MockMvc、AssertJ、嵌入式 Redis | 单元测试、Web 层集成测试、并发及缓存验证 |
| Postman、JMeter、Node.js、Python | 接口集合、HTTP 压测、进程监控；Python 监控使用 psutil |
| Docker Compose（可选） | 启动 MySQL；当前 compose 文件不包含应用或 Redis |

Redis 的两种用途分别配置：搜索缓存默认关闭，nonce 默认存应用内存，因此默认运行和 demo 模式不要求 Redis。项目没有使用消息队列、注册中心或配置中心。

## 架构与功能模块

```text
Swagger / Postman / HTTP 客户端
  → 内嵌 Tomcat
  → Spring Security：请求验签 → Token 查询 → nonce 防重放 → 权限检查
  → Controller：接收请求、校验参数
  → Service：业务规则、事务和并发控制
  → Db / JdbcTemplate → HikariCP → MySQL（demo 使用 H2）
  → Api 统一封装 → Jackson JSON 响应

CatalogService → SearchCountCache → Redis（可选，仅公开列表 total）
SecurityConfig → NonceStore → 内存 / Redis（签名防重放）
```

上面的验签流程适用于携带 Bearer token 且启用签名的请求；不带 token 的公开请求直接进入业务处理。业务异常由 `Errors` 转换为统一错误响应，鉴权和验签错误由安全过滤器返回。

| 模块 | 主要接口路径 | 功能与权限 |
|---|---|---|
| 用户认证 | `/api/auth/*` | 注册、登录公开；当前用户和注销需登录 |
| 商品浏览 | `/api/categories`、`/api/products`、`/api/products/{id}` | 公开查询分类、在售商品、关键词和分类分页 |
| 地址与购物车 | `/api/addresses`、`/api/cart` | 已登录用户维护自己的地址和购物车 |
| 订单与支付 | `/api/orders/*` | 用户下单、查单、取消、模拟支付、查支付记录、确认收货 |
| 商品及库存管理 | `/api/admin/categories/*`、`/api/admin/products/*` | 管理员维护分类、商品、上架状态、库存和流水 |
| 订单履约 | `/api/admin/orders/*` | 管理员查询全站订单并填写物流单号发货 |

完整方法、路径、参数、响应和错误码见 [API 文档](docs/API.md)。金额在 Java 中使用 `BigDecimal`，在数据库中使用 `DECIMAL`。

## 快速启动：无需数据库的演示模式

在 PowerShell 中执行：

```powershell
cd D:\code\EasyMall
mvn -B -ntp clean package
java -jar target/easymall-1.0.0.jar --spring.profiles.active=demo
```

也可运行 `powershell -ExecutionPolicy Bypass -File scripts/start-demo.ps1`。需要 Java 21、Maven 3.6.3+，首次构建需要联网下载依赖。

- Swagger：<http://localhost:8080/swagger-ui/index.html>。先在页面里调用 `POST /api/auth/login`，页面会自动填入 token，之后的请求自动签名（见下文“请求签名”）。
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 健康检查：<http://localhost:8080/actuator/health>
- 演示管理员：`admin / DemoAdmin123!`；普通用户通过注册接口创建。
- 演示模式为 H2 内存库（MySQL 兼容模式），重启后数据清空。启动时创建 1 个分类、20 个商品，每个库存 10000。
- 端口占用时增加 `--server.port=18080`。

## MySQL 8 模式

1. 检查并修改 `scripts/init-mysql.sql` 中的示例数据库密码，然后在 MySQL 管理员客户端执行 `source D:/code/EasyMall/scripts/init-mysql.sql`。脚本不删除已有数据，也不修改已有账号密码。
2. 设置以下环境变量。`ADMIN_PASSWORD` 只用于首次创建管理员，不会覆盖已有账号。生产或共享环境不要使用演示密码。

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/easymall?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME='easymall'
$env:DB_PASSWORD='你在初始化 SQL 中设置的密码'
$env:ADMIN_USERNAME='admin'
$env:ADMIN_PASSWORD='ReplaceWithStrongPassword123!'
$env:SEED_DATA='true'
$env:SIGN_MASTER_KEY='ReplaceWithRandomSigningKeyOf32PlusChars'   # 可选；不设置时每次启动随机生成，重启后需重新登录
java -jar target/easymall-1.0.0.jar --spring.profiles.active=mysql
```

MySQL 是默认配置。Flyway 首次启动自动执行 `src/main/resources/db/migration/V1__schema.sql`，后续启动按迁移历史跳过。不要再手工导入 V1，否则 Flyway 无法正常管理同一库。`SEED_DATA=true` 仅在分类表为空时创建演示商品；不需要样例时删除该环境变量。启用种子数据时需要已有管理员或提供 `ADMIN_PASSWORD`。

如果本机没有 MySQL，可以选择 Docker（需要自行启动 Docker Desktop）：

```powershell
$env:DB_PASSWORD='ReplaceDatabasePassword123!'
$env:MYSQL_ROOT_PASSWORD='ReplaceRootPassword123!'
docker compose up -d
# 将 DB_URL 的端口改为 3307，其余启动步骤相同
```

## 常用配置

配置入口为 `src/main/resources/application.yml` 和 `application-{demo,mysql}.yml`。下表为默认配置；demo 会覆盖管理员密码和种子数据开关。

| 环境变量 / 配置项 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8080` | 应用端口 |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | 本机 3306 的 easymall 库、easymall 用户、空密码 | MySQL 连接信息，实际使用时设置密码 |
| `ADMIN_USERNAME`、`ADMIN_PASSWORD` | `admin`、空 | 可选的首次管理员初始化；空密码时不创建管理员 |
| `SEED_DATA` | `false` | 分类表为空时是否初始化演示数据 |
| `SIGNATURE_ENABLED` | `true` | 是否校验携带 token 请求的签名 |
| `SIGN_MASTER_KEY` | 空 | 空值时进程随机生成；显式配置至少 32 个字符 |
| `SIGN_NONCE_STORE` | `memory` | `memory` 用于单实例，`redis` 用于共享防重放记录 |
| `SEARCH_CACHE_ENABLED`、`SEARCH_CACHE_TTL` | `false`、`60s` | 搜索总数缓存及基础 TTL |
| `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD` | `localhost`、`6379`、空 | Redis 连接信息 |
| `spring.datasource.hikari.maximum-pool-size` | `20` | 数据库连接池上限 |
| `spring.datasource.hikari.connection-timeout` | `10000` 毫秒 | 获取数据库连接的最长等待时间 |
| `app.token-hours` | `12` | 登录令牌有效小时数 |
| `app.signature.max-skew`、`app.signature.max-body-size` | `300s`、`64KB` | 签名时间窗口和签名请求体大小上限 |

未提供专用环境变量的配置项可通过 Spring Boot 命令行参数覆盖，例如 `--spring.datasource.hikari.maximum-pool-size=20`。压测时显式设置 `--debug=false`，避免宿主环境的 `DEBUG` 变量开启调试日志并影响结果。

## 购买闭环

注册 → 登录 → 查询商品 → 新增地址 → 设置购物车 → 下单 → 模拟支付 → 管理员发货 → 用户确认收货。

订单状态：`PENDING → PAID → SHIPPED → COMPLETED`；`PENDING → CANCELLED`。只允许取消未支付订单。

下单请求示例（以运行时返回的地址和商品 ID 为准）：

```http
POST /api/orders
Authorization: Bearer <登录得到的 token>
X-Timestamp: <毫秒时间戳>
X-Nonce: <UUID>
X-Signature: <用登录得到的 signKey 计算的签名>
Idempotency-Key: purchase-20261111-0001
Content-Type: application/json

{"addressId":1,"items":[{"productId":1,"quantity":2}],"clearCart":true}
```

订单金额从数据库价格计算；请求不能传金额或状态。订单保存商品名称、价格和地址快照。`clearCart=true` 仅扣减已购买商品对应的购物车数量，保留多余数量。

### 从启动到订单完成

1. **启动**：`EasyMallApplication` 加载配置和组件，Flyway 迁移表结构，`Bootstrap` 按配置创建管理员、演示商品和 `SEED` 库存流水。
2. **登录**：注册用户的密码经 BCrypt 加密；登录返回 `token`、`signKey` 和过期时间，数据库只保存 token 摘要。客户端随后按签名规则调用个人接口。
3. **选购**：查询商品、维护收货地址、设置购物车。购物车数量是绝对值，不预占库存；也可以不经过购物车直接下单。
4. **下单**：客户端提交地址及商品清单，服务端执行下面的下单事务，成功后得到 `PENDING` 订单，此时已扣减可售库存。
5. **支付或取消**：模拟支付写入 `SUCCESS` 支付记录并将订单改为 `PAID`，不再次扣库存；取消只允许 `PENDING`，回补库存并写入 `RELEASE` 流水。
6. **履约**：管理员对已支付订单填写物流单号，状态变为 `SHIPPED`；用户确认收货后变为 `COMPLETED`。重复取消、重复确认收货不会重复改变库存或状态。

### 下单事务与并发控制

`OrderService.create()` 在同一个数据库事务中按以下顺序执行：

```text
校验 Idempotency-Key → 锁定当前用户行
  → 将商品按 ID 排序，拒绝重复商品，计算下单内容摘要
  → 查询相同用户、相同幂等键的订单：内容相同则返回已有结果，不同则 409
  → 校验地址归属
  → 按 ID 升序锁定商品，校验上架状态、库存，按数据库价格计算金额
  → 创建订单和地址快照
  → 条件扣库存，保存商品名称/单价快照，写入 RESERVE 流水
  → 按需扣减购物车数量 → 提交事务
```

任一步骤失败则回滚，避免订单、库存、购物车只更新一部分。同一用户的相关写操作通过用户行锁串行化；不同用户购买同一商品时竞争商品行锁。商品按固定顺序加锁减少死锁风险，库存条件更新和数据库唯一约束提供额外保护。

### 核心数据表

| 表 | 保存的数据及主要关系 |
|---|---|
| `mall_user`、`auth_token` | 用户、密码摘要、角色；一个用户可有多个登录令牌 |
| `category`、`product` | 分类和商品；商品关联分类并保存当前价格、库存、上架状态 |
| `address`、`cart_item` | 用户地址及购物车；同一用户同一商品只有一条购物车记录 |
| `mall_order`、`order_item` | 订单、收货地址快照、商品名称与价格快照；一单多条明细 |
| `payment` | 模拟支付记录；订单 ID 唯一，防止一个订单重复产生支付记录 |
| `stock_log` | 库存增减流水：`SEED` 初始化、`ADJUST` 调整、`RESERVE` 下单、`RELEASE` 取消 |

建表 SQL 和索引见 [V1__schema.sql](src/main/resources/db/migration/V1__schema.sql)。商品下架、地址删除和价格变更不会修改历史订单快照。

## 项目结构

```text
pom.xml / compose.yml
src/main/java/com/easymall/
  *Controller.java          36 个业务 REST 接口
  AuthService.java          BCrypt 密码、令牌登录/注销
  CatalogService.java       分类、商品、库存调整
  SearchCountCache.java     Redis 搜索总数缓存、失效和故障回退
  CustomerService.java      地址、购物车
  OrderService.java         下单/支付/取消/发货/收货事务
  SecurityConfig.java      Bearer 鉴权、管理员授权、OpenAPI
  RequestSignature.java    请求签名校验；NonceStore.java 防重放
  SwaggerSigning.java      给 Swagger UI 注入自动签名脚本
  Requests.java / Api.java  参数校验和统一响应
  Errors.java / Db.java     异常转换、参数化 SQL、分页
  Bootstrap.java            可选管理员及测试数据初始化
src/main/resources/
  application*.yml
  db/migration/V1__schema.sql
src/test/java/com/easymall/  购买闭环、签名、防重放、缓存和性能测试
scripts/                   MySQL 初始化、演示启动、压测数据准备
postman/                   完整接口集合和本地环境
docs/API.md                请求、响应、接口清单与数据表说明
docs/LOAD_TEST.md          JMeter 场景、数据和验收规则
docs/purchase.jmx          可配置的下单/支付压测模板
docs/VERIFICATION.md       本次实际验证结果与限制
```

## 测试和工具

`mvn test` 运行 H2 集成测试，`mvn package` 同时运行测试并打包。测试包含 HTTP 购买闭环、越权、校验、幂等、抢库存、并发取消、支付取消竞争、快照、请求签名（篡改、过期、重放、并发重放、Redis 不可用）等。搜索总数缓存和 Redis nonce 的测试会自动启动嵌入式 Redis，不需要另外安装。H2 测试不等于 MySQL 压测；实际执行结果见 `docs/VERIFICATION.md`。

独立 MySQL HTTP 压测的启动、场景、监控和复现步骤见 [docs/BENCHMARK.md](docs/BENCHMARK.md)。脚本比较商品读取、关键词分页、分散下单、热门商品和同用户竞争，并核对订单金额及库存流水；无需安装额外 npm 包。

早期 31 组 MySQL 压测及瓶颈分析见 [2026-11-12 压测报告](docs/BENCHMARK-RESULTS-2026-11-12.md)。该报告在加入请求签名之前测得；签名开启后的高并发数据见下文。两轮环境和驱动存在差异，不能将吞吐差异直接归因于某一项代码改动。

### 测试入口与最近验证

| 测试类 | 验证范围 |
|---|---|
| `PurchaseIntegrationTest` | Web 层购买闭环、权限、幂等、防超卖、取消回补、支付取消竞争、回滚与快照 |
| `RequestSignatureTest`、`RequestSignatureIntegrationTest` | 签名算法、参数规范化、篡改、时间窗口、重放与并发重放 |
| `RedisNonceStoreIntegrationTest`、`SignatureDisabledTest` | Redis nonce 的 TTL、防重放及故障拒绝；签名关闭模式 |
| `SearchCountCacheIntegrationTest` | 缓存命中、事务提交后失效、并发回源合并及 Redis 故障恢复 |
| `SearchCountCachePerformanceTest` | 可选的缓存开关、频繁失效和 Redis 宕机性能对比 |

2026-11-12 验证：自动化测试 37 项通过、0 失败，另 1 项性能测试默认跳过；独立 MySQL 环境的 56 次真实 HTTP 请求符合预期，包括注册到收货和取消回补。MockMvc 测试经过 Web 层和安全过滤器，但不等同于启动端口后的网络请求。

缓存性能测试需显式启用，默认使用 H2 和嵌入式 Redis：

```powershell
mvn test "-Dtest=SearchCountCachePerformanceTest" "-Dperf=true"
```

通过 `PERF_DB_URL`、`PERF_DB_USERNAME`、`PERF_DB_PASSWORD` 可以改用专用 MySQL 测试库。它直接调用 `CatalogService.products()`，不包含 HTTP、鉴权和 JSON 序列化耗时；生成 `target/search-cache-perf-h2.md` 或 `target/search-cache-perf-mysql.md`。完整 HTTP 购买压测使用 [JMeter 说明](docs/LOAD_TEST.md) 或 [独立 MySQL 压测说明](docs/BENCHMARK.md)。

### 签名开启后的高并发结果

新增 [高并发原始报告](docs/benchmark-high-concurrency-2026-11-12.json)：32 / 64 / 128 并发共 18 组，每组预热 3 秒、正式测量 20 秒。独立 MySQL 8.0.34，128 个买家、129 个有库存商品、10000 个查询商品；连接池 20，应用最大堆 384 MB。签名开启、nonce 使用内存、Redis 搜索缓存关闭、DEBUG 关闭。

正式测量共 **526801 次请求，0 错误**；包含预热共核对 **147684 笔订单**，订单金额、库存余额和扣库存流水一致。128 并发结果如下，p95 表示 95% 请求在该时间内完成：

| 场景 | 成功请求/秒 | p95 ms | p99 ms |
|---|---:|---:|---:|
| 公开商品详情 | 3441.2 | 82.8 | 114.9 |
| 商品详情（鉴权与签名） | 2443.5 | 112.9 | 153.9 |
| 关键词分页 | 265.0 | 1094.3 | 1508.3 |
| 不同用户、不同商品下单 | 1755.2 | 126.1 | 168.4 |
| 不同用户、同一商品下单 | 193.7 | 875.4 | 1105.3 |
| 同一用户并发下单 | 211.2 | 730.7 | 997.0 |

搜索主要耗时在 `COUNT(*)` 扫描；热点商品和同用户下单主要受行锁竞争限制。该轮 Node 驱动在正式计量前原生崩溃，实际数据由 Python 线程驱动采集。应用、数据库和驱动同机，属于短时闭环测试，压测端可能限制快速接口吞吐，不能作为生产容量上限或与旧报告直接比较。下单场景只创建 `PENDING` 订单，不包含支付吞吐；也没有测量开启 Redis 搜索缓存后的 HTTP 性能。

导入 `postman/EasyMall.postman_collection.json` 与 `postman/local.postman_environment.json`。按文件夹顺序运行，集合会自动保存 token、signKey、商品、地址和订单 ID，并由集合级 Pre-request Script 给带 token 的请求签名。每次运行会生成新的用户名与幂等键。管理员密码默认只适用于 demo，MySQL 模式请修改环境变量。

## 已实现的保护及设计取舍

- 密码 BCrypt，登录令牌为随机 256 bit，数据库只保存 SHA-256 摘要，12 小时到期；注销立即撤销当前令牌。
- 用户仅能操作自己的地址、购物车、订单和支付；管理接口需 ADMIN 角色。用户注册不能指定角色。
- 下单/支付/取消/库存调整均在数据库事务内；同一用户写操作串行化，商品按 ID 顺序加行锁，带库存下限条件更新，避免超卖及重复回补。
- 下单、支付、库存调整需要 `Idempotency-Key`（8–64 个字母数字或 `._:-`）。相同键相同业务返回已提交结果；相同键不同内容返回 409。数据库唯一约束提供兜底。幂等键按用户、业务类型隔离，不设置自动过期。
- 所有业务接口返回 HTTP 状态码及 `{code,message,data,timestamp}`；分页统一为 `{items,total,page,size}`。健康检查和 OpenAPI 文档遵循自身标准格式。
- SQL 值参数化；分页 size 最大 100；单订单最多 50 种商品、每种最多 999 件；用户名统一小写；密码限制为 8–64 位非空格可打印 ASCII 字符。
- 商品删除为下架；有关联商品的分类不能删除。地址删除不会影响历史订单。

## 请求签名

带 Bearer token 的请求都必须用登录返回的 `signKey` 做 HMAC-SHA256 签名，签名覆盖方法、路径、query、时间戳、nonce 和请求体哈希。完整规则、示例和错误码见 [docs/API.md](docs/API.md#请求签名)。

- `signKey` 由服务端主密钥和 token 推导，不存库。不同登录的 `signKey` 不同，注销或过期后随 token 失效。只拿到 token 而没有 `signKey` 无法调用接口。
- 时间戳超出 ±300 秒的请求会被拒绝；同一 token 下重复的 nonce 返回 409，所以截获的请求不能重放。
- 校验顺序是先验签名再查 token，伪造请求不会访问数据库。
- nonce 默认存内存（单实例）。多实例部署设置 `SIGN_NONCE_STORE=redis` 共享 nonce，Redis 出错时拒绝请求（503），不会跳过校验。
- 多实例部署或希望重启后 token 仍可用时，设置 `SIGN_MASTER_KEY`（至少 32 个字符）。
- `SIGNATURE_ENABLED=false` 可以关闭签名校验，只保留 token 认证。
- Swagger、Postman、JMeter 模板、`prepare-load-data.ps1` 和 `benchmark.cjs` 都已经带上签名逻辑。

签名防重放和业务幂等是两层保护：业务重试沿用原 `Idempotency-Key` 和相同业务内容，但必须生成新的 `X-Nonce`、时间戳和签名，不能原样重发旧签名请求。签名不加密传输内容。

## 可选：Redis 搜索总数缓存

公开商品列表 `GET /api/products` 的 `total` 来自 `COUNT(*)`，关键词搜索时这条语句占了压测中几乎全部耗时。开启后 `total` 缓存在 Redis，商品明细（当前页）仍每次查库。默认关闭，demo 模式不需要 Redis。

```powershell
$env:SEARCH_CACHE_ENABLED='true'
$env:REDIS_HOST='localhost'      # 默认 localhost
$env:REDIS_PORT='6379'           # 默认 6379
$env:REDIS_PASSWORD=''           # 有密码时设置
$env:SEARCH_CACHE_TTL='60s'      # 默认 60s，实际过期时间再加 0~20% 随机值
```

- 缓存键由分类、关键词的 SHA-256 和全局版本号组成；管理端列表 `/api/admin/products` 不走缓存，始终精确。
- 新增、修改、下架商品时递增版本号，使旧总数不再被后续缓存查询使用；有事务时在提交后执行，无事务时立即执行。扣库存不影响总数，不触发失效。
- 直接改库（绕过应用）的变更要等旧条目过期才反映到缓存总数；默认实际 TTL 为 60～72 秒。
- 同一实例内同一个键缓存未命中时只有一个请求查库，其他请求等待它的结果；多实例时最多每个实例查一次。
- Redis 不可用时自动回退为直接查库：Redis 连接和命令超时均配置为 200ms，失败后 5 秒内跳过 Redis；这不是整个 HTTP 请求耗时的上限。期间发生的失效会在 Redis 恢复后先补做，再使用缓存。

注意：上述回退只适用于搜索缓存。若设置 `SIGN_NONCE_STORE=redis`，Redis 不可用时带 token 的有效签名请求会返回 503，避免失去防重放保护。当前配置禁用了 Redis 健康指标，因此 `/actuator/health` 为 UP 不能单独证明 Redis nonce 存储可用。

