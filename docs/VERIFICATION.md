# 实际验证记录

日期：2026-11-11（Asia/Shanghai）。目标目录 `D:\code\EasyMall` 在开始时为空；未覆盖用户已有文件。旧会话全文读取因凭证过期失败，实施范围以本次用户明确要求为准。

## 环境

- Windows 11；Java 21.0.7；Maven 3.9.15。
- Spring Boot 3.5.7；Springdoc 2.8.14；H2 2.3.232。
- MySQL 8.0.34：使用本机已有 MySQL 程序新建独立临时数据目录，监听 `127.0.0.1:13316`，数据库 `easymall_verify`。未连接或改动原有 MySQL 服务中的业务数据。
- Docker 引擎未运行，因此没有测试 Docker Compose 启动流程。

## 已执行

| 检查 | 实际结果 |
|---|---|
| Maven 编译、测试、可执行 JAR 打包 | 成功；最终使用 clean package 重新构建 |
| H2 集成测试 | 12 tests，0 failures，0 errors，0 skipped |
| MySQL 集成测试 | 同一套 12 tests，0 failures，0 errors，0 skipped |
| 演示 profile 的可执行 JAR 启动 | 成功；HTTP health=UP |
| MySQL profile 的可执行 JAR 启动 | 成功；HTTP health=UP |
| Flyway 首次建表及再次启动 | 首次成功创建业务表及版本历史；再次启动校验通过且未重复建表 |
| Postman/Newman → H2 实际 HTTP | 49 requests，98 assertions，0 failed |
| Postman/Newman → MySQL 实际 HTTP | 49 requests，98 assertions，0 failed |
| OpenAPI 与 Postman 覆盖对照 | 36 个方法/路径组合，未覆盖数 0 |
| Swagger HTML / OpenAPI JSON | HTTP 200；JSON 导出为 docs/openapi.json |
| 压测数据准备脚本 | MySQL 环境实际生成 10 个独立买家和地址、1 个专用分类及库存 1000 的商品 |
| MySQL 负库存检查 | 0 条 |
| MySQL 库存余额与流水 SUM(delta) 不一致 | 0 条 |
| MySQL 重复支付订单检查 | 0 条 |
| JMeter 模板 | XML 结构解析通过；未在 JMeter 中执行 |

集成测试覆盖：完整下单支付发货收货、8 路同键下单/支付、10 买家争抢 3 件库存（3 成功、7 库存冲突）、8 路取消仅回补一次、支付与取消竞争、库存不足无部分业务写入、越权与管理员权限、注销与 token 过期、无效参数、幂等请求内容冲突、并发库存调整、历史价格地址快照、注册大小写规范及重复账号。

测试证据：`test-results-h2.txt`、`test-results-mysql.txt`、`http-verification.json`。Newman 完整原始报告含动态 token，未放入项目交付文档。

## 修复与验证边界

首轮 H2 测试发现通用 JDBC generated keys 同时返回 id 和默认时间列，已改为明确请求 id 列，之后两种数据库测试通过。另修正了 null 订单行校验、密码 BCrypt 字节长度边界，并将 MySQL JDBC 会话时间统一到 UTC。

构建期间曾遇到 Windows 对正在运行 JAR 的占用，导致一次重打包及当时的 MySQL HTTP 验证中断。已停止服务、重新打包，再从独立运行副本启动并完整重跑 MySQL HTTP 集合，最终 49 请求及 98 断言通过；中断那轮不计为通过。

这些是功能集成与小规模并发正确性测试，不是持续高负载性能测试。没有测得可用于容量规划的 QPS、p95 或 p99，也没有运行 JMeter 正式压测。JMeter 脚本需要按 `LOAD_TEST.md` 在目标环境实际运行。

压测数据中的实际 token 属于临时验证数据库，不能用于新启动的演示库或用户自己的 MySQL。已移除项目内这份临时 CSV；使用前重新执行 `scripts/prepare-load-data.ps1`。

真实支付、退款/售后、超时自动关单、SKU、优惠券、限流、缓存、消息队列及前端均未实现。具体边界见 README。

## 复现 MySQL 测试

先新建一个专用空测试数据库（不要使用生产库），然后：

```powershell
$env:DB_USERNAME='专用测试数据库用户'
$env:DB_PASSWORD='该用户密码'
powershell -ExecutionPolicy Bypass -File scripts/test-mysql.ps1 -TestDatabaseUrl 'jdbc:mysql://localhost:3306/easymall_test?serverTimezone=UTC&forceConnectionTimeZoneToSession=true'
```

测试会自动迁移、创建演示管理员和商品，并继续新增测试数据；不执行清库操作。该脚本使用 demo 的测试初始化设置，但覆盖为 MySQL 数据源。若重复使用测试库，需确保 demo 管理员密码未被改变。
