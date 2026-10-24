# 重启稳定性补测（2026-10-25）

**PASS：新增 8 个用例，连同原故障回归共 42 个用例通过，0 失败、0 跳过；最终执行耗时 101.62 秒。**用户排除持续运行测试；本轮无 benchmark、小时级压测或生产参数变更。

| 新用例 | 检查内容 |
|---|---|
| 1000 次 SDK 生命周期 | 同组新 Worker、16000 个 ID 查重、运行期零 JDBC、关闭幂等、连接和 SDK 线程归零；弱引用回收探针检查关闭钩子等未保留实例 |
| 20 轮真实进程替换 | 21 个 JVM 重叠启动，交替 10 次 SIGTERM / 10 次强杀，末实例继续发号；正常退出 OFFLINE、强杀残留 ONLINE |
| 50 次同槽位占用/释放 | 真实 ZK ephemeral 节点移除、后继重新占用；不把占位复用当作同秒 ID 唯一性证明 |
| 25 次失败 + 25 次正常 JS 启动 | 数据库已提交后 ZK 拒绝占位，Worker 注销、连接和客户端线程清理、成功实例节点释放 |
| 带流量 ZK 抖动 | 5 次断连后同会话恢复；会话过期后 100 次调用拒绝，替代实例可发号；零运行期 JDBC |
| 小缓存并发 | 容量 2/4、8 消费者跨圈共 20000 条；50 轮四消费者与双关闭调用竞争，领取不重复、租约只释放一次 |
| 时间边界 | 100 次秒切换，墙钟偏差 ±999/±1000ms 接受、±1001ms 拒绝 |

1000 次 SDK 重建不是 1000 次 JVM 重启；真实进程测试是 20 轮替换。资源探针验证确定的连接、线程、节点及对象保留，不代表完整堆分析或所有文件描述符的长期泄漏证明。

## 入口

测试均为 Java/JUnit。有独立测试 MySQL 时可直接执行（构建 JDK 21）：

```sh
./mvnw -pl smartscity-id-spring-boot-autoconfigure -am test \
  -Did.test.restart=true \
  '-Did.test.mysql.url=jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true' \
  -Dtest=RestartConcurrencyTest,RestartStabilityTest,ZookeeperRestartStabilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

数据库入口限定 `127.0.0.1:13367/smartscity_id_test`，测试账号 root、空密码，必须是隔离实例。ZK 由 JUnit 创建临时服务器。`id.test.restart` 默认未启用，普通构建不运行这些循环。

也可复用已有执行器自动创建/清理隔离 MySQL：

```sh
python3 testing/run-destructive.py --run --restart --output /tmp/id-restart-result
```

执行器仅管理环境和 Maven；新增检查均在 Java。该命令额外执行已有故障回归与停机测试；Maven 主阶段上限 420 秒，停机阶段 40 秒，预计几分钟。

## 归属与复审

| 问题 | 取舍 | 归属 |
|---|---|---|
| JDBC 实际关闭与运行期访问观测 | 测试 Decorator，不改生产 DataSource | RestartDatabase 代理计数，实际调用真实 MySQL |
| 生命周期组装 | 复用生产 Factory/Builder | 每个用例拥有并关闭 generator、子进程和 executor |
| ZK 故障与复用 | 独立真实服务器夹具 | 用例持有会话；AfterEach 停服并检查新增客户端线程退出 |
| 时钟与并发 | 复用 TimeSource 测试端口与公共 RingBuffer API | 不增加生产测试钩子，不操作宿主机时间 |

Worker 取模跨圈、同秒复用及已在途调用风险维持原契约。此轮不声称生产滚动发布、整机休眠或长期稳定性已经验证。

## 实测结果与边界

- 1000 次 SDK 创建/关闭：16000 个成功 ID 查重通过，1000 个不同 Worker；全部 OFFLINE，关闭对象回收探针 1000/1000，连接和 SDK 线程归零。
- 20 轮进程替换：21 个真实 JVM、2816 个 ID 无重复；10 个强杀 Worker 留 ONLINE，11 个正常结束 Worker 为 OFFLINE，均符合弱生命周期设计。
- ZK：50 次同槽释放复用、25 次失败/25 次成功 JS 启动通过；5 次带流量断连观察到 COORDINATION 拒绝后恢复原会话；过期后 100 次调用拒绝。客户端线程和占位清理通过。
- 50 轮关闭竞争、小缓存 20000 条跨圈和 100 次时间边界循环通过。生产代码未改动；不把这些有限轮次结果外推为长期稳定性或全平台兼容证明。
- 环境：JDK 21.0.12.1 / macOS 26.5.1，真实 MySQL 9.6 和 ZK 3.9.4；JDK 9/17 未执行本轮新增套件。未跑持续运行和 benchmark。

[最终原始证据](evidence/id-restart-20261025-verified/README.md)、[计数与清理结果](evidence/id-restart-20261025-verified/result.json)。完整日志、源码指纹及重试记录均保留。首次夹具 import 错误导致 testCompile 失败，修正后完整通过；随后收紧 ZK 断言，再次完整通过。
