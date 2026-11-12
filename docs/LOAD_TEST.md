# JMeter 压测准备与接口说明

## 数据准备

只对隔离测试环境压测。先启动应用，然后在项目根目录运行：

```powershell
$env:ADMIN_PASSWORD='DemoAdmin123!' # demo 示例；MySQL 使用实际管理员密码
powershell -ExecutionPolicy Bypass -File scripts/prepare-load-data.ps1 -Users 100 -BaseUrl http://localhost:8080
```

脚本新建专用分类、商品（库存为用户数×100）、独立用户及地址，并导出 `data/jmeter-users.csv`。列为 `username,password,token,addressId,productId,signKey`，`signKey` 用于给同一行 token 的请求签名。令牌有效 12 小时；CSV 含凭证，应保存在本地。每次使用新 RunId，脚本不清空已有数据；重复指定同一 RunId 会因唯一约束失败。数据准备涉及 BCrypt，耗时不应计入购买接口延迟。

## 随附模板

`docs/purchase.jmx` 使用 JMeter 标准组件，无第三方插件。默认 10 并发、1 次循环、5 秒启动间隔，每次循环从 CSV 取一个独立用户，下单后支付。线程组级 JSR223 前置处理器（Groovy）给每个请求计算 `X-Timestamp`、`X-Nonce`、`X-Signature`，规则见 [API.md](API.md#请求签名)；新增的采样器放在同一线程组下会自动签名。CSV 不循环，耗尽即停止线程。默认接口 `http://localhost:8080`，数量 1。

```powershell
jmeter -n -t docs/purchase.jmx -Jhost=localhost -Jport=8080 -Jprotocol=http -Jthreads=10 -Jloops=1 -Jramp=5 -Jusers=data/jmeter-users.csv -l data/results.jtl -e -o data/report
```

命令应从项目根目录运行，`data/report` 必须不存在或为空。CSV 行数应至少等于 threads×loops；token 到期后重新生成数据。下单和支付各自使用 UUID 幂等键；JSON 提取器从 `$.data.id` 保存订单 id，仅在下单成功得到 id 时支付。断言检查 HTTP 200 和业务 code 为 OK。请先用 1 用户、1 循环检查数据和环境，再逐级加压。

## 建议场景与断言

| 场景 | 请求 | 校验要点 |
|---|---|---|
| 商品读 | GET /api/products?page=1&size=20；GET /api/products/{id} | 200，items/商品字段完整；单独测不同分页和关键词 |
| 登录 | POST /api/auth/login | 200，有 token；独立报告 BCrypt 开销 |
| 多用户购买 | POST /api/orders → POST /api/orders/{id}/pay | 订单与支付金额一致；库存减少成功下单数量；没有重复支付 |
| 幂等重试 | 同一用户、同一请求体、同一键并发下单/支付 | 所有成功响应指向同一 id；仅一笔扣库存与支付 |
| 超卖竞争 | 准备库存 10，50 个不同用户同时购买 1 件 | 10 个成功，40 个 409 库存不足；最终库存 0 |
| 取消回补 | 下单后并发 POST /api/orders/{id}/cancel | 取消请求可重复成功，但 RELEASE 流水只出现一次 |
| 支付取消竞争 | 同订单同时 pay/cancel | 一方成功另一方 409；PAID 时不回补，CANCELLED 时无支付记录 |
| 权限与非法参数 | 无 token、USER 访问 admin、访问其他用户订单、数量 0、size 101 | 分别 401、403、404、400，不应出现 500 |

模板实现的是“多用户购买”场景，其他场景按表单独建线程组；不要把预期的 409 算作系统故障，也不要让普通成功购买模板把 409 当作通过。已包含的 Java 集成测试可以验证上述核心并发不变量，但不能替代真实负载下的性能报告。

## 记录与数据库核对

记录环境（CPU、内存、Java/MySQL 版本、连接池 20）、线程数、持续时间、吞吐量、p50/p95/p99、错误率、数据库连接和锁等待。先测读取，再测正常购买，再测单热销商品锁竞争。同一用户写操作被串行化，应使用独立账号而不是所有线程共用一个 token。

```sql
-- 以下 product_id 替换为准备脚本打印的专用商品 ID
SELECT id, stock FROM product WHERE id = 1;
SELECT reason, COUNT(*), SUM(delta) FROM stock_log WHERE product_id = 1 GROUP BY reason;
SELECT product_id, SUM(delta) AS ledger_stock FROM stock_log WHERE product_id = 1 GROUP BY product_id;
SELECT order_id, COUNT(*) FROM payment GROUP BY order_id HAVING COUNT(*) > 1;
SELECT id, stock FROM product WHERE stock < 0;
SELECT id, status, total_amount FROM mall_order ORDER BY id DESC LIMIT 20;
```

库存流水增减总和应等于商品可售库存（包括初始 SEED/ADJUST），后两类异常查询应为空。每轮压测使用专用商品和新数据，报告以实际执行结果为准，不预设吞吐量目标。
