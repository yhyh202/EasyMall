# 可复现的 MySQL HTTP 基线压测

本流程只创建独立 MySQL 实例和专用数据库，不连接现有业务数据库。需要 Java 21、Maven、Node.js（无需 npm 依赖）和本机 MySQL 8 二进制程序。压测会使用 CPU、内存和磁盘，请先保存其他工作。

本次实际结果见 [2026-11-12 基线分析报告](BENCHMARK-RESULTS-2026-11-12.md)，包含 31 组测量及 SQL、锁等待、CPU 证据。

## 运行

```powershell
mvn -B -ntp package
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-benchmark.ps1 -MySqlBin 'D:\develop\mysql-8.0.34-winx64\bin'
# 将下面的路径换成上一条命令输出的实际 state.json 路径
node scripts/benchmark.cjs --state=data/benchmark-YYYYMMDD-HHMMSS/state.json --prepare=true --prepare-only=true
node scripts/benchmark.cjs --state=data/benchmark-YYYYMMDD-HHMMSS/state.json --seconds=15 --warmup=3 --levels=1,8,32,64
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stop-benchmark.ps1 -StateFile data/benchmark-YYYYMMDD-HHMMSS/state.json
```

启动脚本默认仅监听 `127.0.0.1`，MySQL 使用 13327 端口，应用使用 18081 端口。端口占用时脚本停止，可以通过 `-DatabasePort` 和 `-AppPort` 指定其他端口。Java 堆上限 384 MB、InnoDB buffer pool 256 MB、应用连接池 20；MySQL 保持默认提交刷盘设置，未通过放松持久性来提高成绩。只在隔离应用中开放需要鉴权的 Actuator metrics；业务源码和普通启动配置不变。

初始化数据：64 个买家（独立 token、signKey、地址）、65 个库存充足的测试商品，以及 10000 个不参与购买的零库存查询商品。用户注册和登录初始化不计入下单计时。所有订单和库存流水保留，验证时按本轮幂等键前缀统计。停机后不自动删除数据。带 token 的请求由脚本签名（docs/API.md“请求签名”），签名计算计入请求延迟；加入签名之前生成的 `dataset.json` 没有 signKey，需要新建环境重新准备数据。

`data/` 已被 Git 忽略，其中包含临时数据库密码、测试 token 和数据库文件，不应提交或公开。对外报告只保留去除凭证后的结果 JSON。

## 场景

| 参数名称 | 场景 | 用途 |
|---|---|---|
| product_public | 不带 token 的热门商品详情 | 公开读取基线 |
| product_authenticated | 带 token 的相同商品详情 | 对照鉴权额外查询和签名校验 |
| catalog_search | 1 万条匹配记录上的关键词、分类、分页查询 | 分析 LIKE、COUNT 和扫描成本 |
| order_distributed | 不同用户购买各自的商品 | 分散写入基线 |
| order_hotspot | 不同用户购买同一个商品 | 定位商品行锁竞争 |
| order_same_user | 同一用户购买不同商品 | 定位用户行锁串行化 |
| login（可选） | 对已有账号反复登录 | 独立测 BCrypt 和 token 写入；不混入购买基线 |

例如单独重复热点场景：

```powershell
node scripts/benchmark.cjs --state=data/benchmark-YYYYMMDD-HHMMSS/state.json --scenarios=order_hotspot,order_distributed --levels=64 --warmup=5 --seconds=30
```

默认每个 worker 持续串行发送请求，形成指定数量的并发在途请求，使用 HTTP keep-alive，无思考时间、无自动重试。这是闭环负载模型：服务变慢时请求到达率会下降，不能据此直接声称可抵御固定到达率的流量洪峰。超过 64 并发会复用部分买家，分散商品场景也会产生用户/商品竞争，因此对当前数据集主要使用不超过 64 的梯度。

## 结果与证据

每完成一组便写入 `data/benchmark-.../results-时间戳.json`，包括：

- 成功吞吐量、请求数、HTTP 状态分布、错误、平均/p50/p95/p99/最大延迟。计时包含请求发送、网络、响应接收及 JSON 校验；吞吐量分母包含最后一批请求排空时间。
- MySQL `Innodb_row_lock_waits`、`Innodb_row_lock_time` 等计数差值，以及 Performance Schema SQL digest 的执行次数、累计时间和扫描行数差值。
- 约每秒采样应用连接池 active/pending、应用/系统 CPU 指标，以及当前锁等待关系所在表。`lockWaitEdges` 统计请求锁与阻塞锁之间的关系条数，不等于等待线程数。SQL 累计时间可高于墙钟时间，表示多个线程累计耗时；行锁累计等待时间也不能作为单次请求延迟。
- 连接获取、连接使用、GC 指标的前后快照，压测端单核 CPU 使用率和事件循环延迟。监控请求不计入业务吞吐量，但其小量查询会计入数据库统计。
- 每组购买测试结束后，用数据库检查暖场与正式请求的订单数、金额、库存与流水余额，以及每单唯一的扣库存流水。

现有集成测试另外覆盖幂等、超卖、取消与支付竞争、事务回滚。新增的独立买家/商品测试及多商品反序请求测试验证并发正确性，不使用机器相关的耗时断言，不能替代压测。

可选的操作系统进程采样脚本 `scripts/monitor-benchmark.py` 需要 Python 与 psutil；本机本次已有该依赖。它统计 Java/MySQL 启动器及实际工作子进程，不将启动器的零 CPU 误当成服务 CPU。可在另一个终端运行：

```powershell
python scripts/monitor-benchmark.py data/benchmark-YYYYMMDD-HHMMSS/state.json 120
```

输出为同目录 `host-monitor-v2.jsonl`。进程 CPU 的 100% 表示一个逻辑 CPU，转换为整机占比需除以逻辑 CPU 数量。脚本最多运行指定秒数，也可在该目录创建 `monitor.stop` 提前结束；再次采样前应移除这个停止标记。

## 解读边界

应用、MySQL 和压测端同机，且同一实例连续执行各场景；JIT、缓存预热、后台活动和订单数量增长会影响结果。15 秒的梯度测试用于初筛，需要将异常场景以更长时间、相反顺序复测。检查 MySQL 锁等待、连接池及 SQL 证据后再归因，不能只比较两个 QPS 数字。正式容量规划还需独立压测机、更长稳态测试、真实数据分布、目标错误预算，以及固定到达率/突发负载测试。
