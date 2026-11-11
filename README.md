# EasyMall 轻量级电商订单管理系统

Java 21 + Spring Boot 3.5.7 + Spring JDBC + Spring Security + MySQL 8，Maven 单体后端。主要用途是接口功能覆盖、事务验证和后续 JMeter 压测。没有前端页面；Swagger 可直接调试。

## 快速启动：无需数据库的演示模式

在 PowerShell 中执行：

```powershell
cd D:\code\EasyMall
mvn -B -ntp clean package
java -jar target/easymall-1.0.0.jar --spring.profiles.active=demo
```

也可运行 `powershell -ExecutionPolicy Bypass -File scripts/start-demo.ps1`。需要 Java 21、Maven 3.6.3+，首次构建需要联网下载依赖。

- Swagger：<http://localhost:8080/swagger-ui/index.html>
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

## 购买闭环

注册 → 登录 → 查询商品 → 新增地址 → 设置购物车 → 下单 → 模拟支付 → 管理员发货 → 用户确认收货。

订单状态：`PENDING → PAID → SHIPPED → COMPLETED`；`PENDING → CANCELLED`。只允许取消未支付订单。

下单请求示例（以运行时返回的地址和商品 ID 为准）：

```http
POST /api/orders
Authorization: Bearer <登录得到的 token>
Idempotency-Key: purchase-20261111-0001
Content-Type: application/json

{"addressId":1,"items":[{"productId":1,"quantity":2}],"clearCart":true}
```

订单金额从数据库价格计算；请求不能传金额或状态。订单保存商品名称、价格和地址快照。`clearCart=true` 仅扣减已购买商品对应的购物车数量，保留多余数量。

## 项目结构

```text
pom.xml / compose.yml
src/main/java/com/easymall/
  *Controller.java          36 个业务 REST 接口
  AuthService.java          BCrypt 密码、令牌登录/注销
  CatalogService.java       分类、商品、库存调整
  CustomerService.java      地址、购物车
  OrderService.java         下单/支付/取消/发货/收货事务
  SecurityConfig.java      Bearer 鉴权、管理员授权、OpenAPI
  Requests.java / Api.java  参数校验和统一响应
  Errors.java / Db.java     异常转换、参数化 SQL、分页
  Bootstrap.java            可选管理员及测试数据初始化
src/main/resources/
  application*.yml
  db/migration/V1__schema.sql
src/test/java/com/easymall/PurchaseIntegrationTest.java
scripts/                   MySQL 初始化、演示启动、压测数据准备
postman/                   完整接口集合和本地环境
docs/API.md                请求、响应、接口清单与数据表说明
docs/LOAD_TEST.md          JMeter 场景、数据和验收规则
docs/purchase.jmx          可配置的下单/支付压测模板
docs/VERIFICATION.md       本次实际验证结果与限制
```

## 测试和工具

`mvn test` 运行 H2 集成测试，`mvn package` 同时运行测试并打包。测试包含 HTTP 购买闭环、越权、校验、幂等、抢库存、并发取消、支付取消竞争、快照等。H2 测试不等于 MySQL 压测；实际执行结果见 `docs/VERIFICATION.md`。

导入 `postman/EasyMall.postman_collection.json` 与 `postman/local.postman_environment.json`。按文件夹顺序运行，集合会自动保存 token、商品、地址和订单 ID。每次运行会生成新的用户名与幂等键。管理员密码默认只适用于 demo，MySQL 模式请修改环境变量。

## 已实现的保护及设计取舍

- 密码 BCrypt，登录令牌为随机 256 bit，数据库只保存 SHA-256 摘要，12 小时到期；注销立即撤销当前令牌。
- 用户仅能操作自己的地址、购物车、订单和支付；管理接口需 ADMIN 角色。用户注册不能指定角色。
- 下单/支付/取消/库存调整均在数据库事务内；同一用户写操作串行化，商品按 ID 顺序加行锁，带库存下限条件更新，避免超卖及重复回补。
- 下单、支付、库存调整需要 `Idempotency-Key`（8–64 个字母数字或 `._:-`）。相同键相同业务返回已提交结果；相同键不同内容返回 409。数据库唯一约束提供兜底。幂等键按用户、业务类型隔离，不设置自动过期。
- 所有业务接口返回 HTTP 状态码及 `{code,message,data,timestamp}`；分页统一为 `{items,total,page,size}`。健康检查和 OpenAPI 文档遵循自身标准格式。
- SQL 值参数化；分页 size 最大 100；单订单最多 50 种商品、每种最多 999 件；用户名统一小写；密码限制为 8–64 位非空格可打印 ASCII 字符。
- 商品删除为下架；有关联商品的分类不能删除。地址删除不会影响历史订单。

## 未实现的扩展

真实支付网关/回调签名、退款/售后、自动超时关单、优惠券、商品 SKU/图片上传、物流查询、短信/邮箱验证、找回密码、刷新令牌、封禁用户、后台用户管理、限流、Redis/消息队列、监控面板、前端和多服务拆分均未实现。模拟支付仅代表成功，不产生真实资金操作。待支付订单不会自动释放库存，需要手动取消。支付后不能取消或退款。

本项目适合本地功能验证和压测起点；正式公开部署前应配置 HTTPS、受控文档访问、登录防暴力措施及运维策略。数据库令牌认证会有额外查询，同一用户高并发会串行等待，压测时应使用多用户数据。
