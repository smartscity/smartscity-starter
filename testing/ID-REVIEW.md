# ID 代码复审与修复

2026-10-25。用户授权整体复审并直接修复；性能测试不运行。本轮检查 API/SPI、Core、JDBC/ZK、工厂与关闭、Spring 装配和观测。

| 优先级 | 问题与修复 | 回归证据 |
|---|---|---|
| P1 | 两个时钟读数之间的线程暂停被误判为回拨；锚定和运行采样改为前后 nanoTime 包围墙钟读取，丢弃被暂停打断的样本，有界重试，不重新锚定 | ClockAndLifecycleTest 两个暂停采样用例；原先失败 |
| P1 | 数据库校验后重新取墙钟，存在漏掉回拨的空档；工厂现在验证并传递同一个 MonotonicClock | rollbackAfterStartupValidationCannotBeHiddenByANewAnchor |
| P1 | 生产线程遇到 Error 退出后仍显示就绪、可继续吐缓存；现在先关闭发号门禁，VM 致命错误仍上抛 | producerErrorPermanentlyClosesIssuanceGate；原先失败 |
| P1 | ZK close(timeout) 不限制 closeSession 请求；关闭可能越过预算，且吞掉调用方中断 | 真实 TCP 停止收包、预置中断两个用例；原先失败；另确认客户端线程退出 |
| P2 | 构造早期、Error、启动取消、ZK 占位失败的资源归属不完整；提前拥有已提交 Worker，清理覆盖异常路径，取消不丢弃清理任务 | 构造失败、驱动 LinkageError、忽略取消的迟到驱动用例；原先失败。另补真实 ZK 拒绝占位后的 Worker 清理用例 |
| P2 | Statement 设置超时失败未显式关闭；rollback 失败覆盖原始异常 | statementSetupFailureClosesStatementAndPreservesCause；原先失败 |

## 状态与模式复审

| 对象 / 模式 | 本轮决定 |
|---|---|
| MonotonicClock（不可变锚点） | 校验与发号共用一个对象；快照保持只读 |
| IdGeneratorFactory（现有 Factory） | 持有启动结果及取消状态；按资源取得顺序转移所有权，异常保留原始原因 |
| CachedUniqueIdGenerator（现有生命周期 owner） | 构造失败释放 lease，生产故障终止发号，close 仍幂等 |
| WorkerAllocation / ZK Adapter | 每实例一次清理；ZK 和数据库共享外部释放预算，中断只催促清理，不取消未执行的任务 |
| RingBuffer | CAS 领取、轮次和 acquire/release 协议复核后保持不变；既有跨圈并发检查加关闭竞争检查 |

不新增通用状态框架，不改变布局、Epoch、普通档取模、运行期不访问 MySQL 和 ZK 不逐秒写进度的设计。普通档跨圈、JS 同秒复用和过期旧进程的已披露风险仍存在。

时钟采样内部最多尝试三次，单次两个 nanoTime 之间超过 1ms 时重新采样；持续无法稳定取样时停发。墙钟偏差默认仍为 1s。关闭先拒绝发号，等待生产者最多 1s，再由外部释放任务共享 shutdown-timeout；异常驱动若忽略中断，资源可能延迟释放，但不重新启用 SDK。

## 验证

修复前 9 个定向用例实际失败；修复后通过。另补同一锚点校验、并发关闭和 ZK 拒绝占位清理三个用例。完整真实数据库/SDK、兼容矩阵及最终制品检查结果以 [门禁记录](GATE-RESULTS.md) 为准；初步无数据库回归的跳过项不算通过。

源代码依据：[ZooKeeper 3.9.4 的 close 实现](https://github.com/apache/zookeeper/blob/release-3.9.4/zookeeper-server/src/main/java/org/apache/zookeeper/ZooKeeper.java)、[ClientCnxn 关闭过程](https://github.com/apache/zookeeper/blob/release-3.9.4/zookeeper-server/src/main/java/org/apache/zookeeper/ClientCnxn.java)、[nanoTime 契约](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime())。
