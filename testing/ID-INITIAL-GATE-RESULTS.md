# ID 实现验收

2026-10-25，macOS 26.5.1 x86_64，JDK 21.0.12.1 / Maven 3.9.15。已按确认方案实现；未提交、推送或发布。性能测试按用户要求 **NOT_RUN**。

| 检查 | 结果与证据 |
|---|---|
| 全模块构建 | PASS：[clean install](evidence/id-implementation/full-build.txt)，启用 verification，真实 MySQL / PostgreSQL / ZK 用例通过；停机用例单独通过 |
| 核心与并发 | PASS：[11 个用例](evidence/id-implementation/core-final.txt)，含位边界、时钟、CAS 跨圈、等待、中断、关闭及回绕风险复现 |
| 真实数据库与进程 | PASS：MySQL 9.6.0、PostgreSQL 14.15；启动分配、配置冲突、正常退出、重叠子进程、kill -9、运行期无数据库连接；见全模块日志 |
| JavaScript 完整链路 | PASS：[5 个 ZK 用例](evidence/id-implementation/zookeeper-full-sdk.txt)，含 MySQL + ZK + SDK 缓存失效停发 |
| MySQL 实际停机 | PASS：[实际停机](evidence/id-implementation/mysql-outage.txt)后原 SDK 连续获取 256 个不重复 ID 且保持就绪，新 SDK 启动失败 |
| Boot 集成 | PASS：2.7.18、3.3.6、3.3.13、3.4.13、3.5.16、4.0.9、4.1.2，见[矩阵](../docs/03-Compatibility-Matrix.md) |
| JDK 运行 | PASS：[同一制品在 JDK 17 / 21 运行](evidence/id-implementation/runtime-probe.txt)，五种布局生成及解析 |
| 生产边界 | PASS：[依赖、Java 9 字节码、自动配置/配置元数据与 JAR SHA-256](evidence/id-implementation/artifacts.txt) |
| 用户示例 | PASS：[构建](evidence/id-implementation/example-build.txt)、[实际生成 ID 并退出](evidence/id-implementation/example-run.txt) |

共 33 个不同功能用例通过，兼容矩阵重复运行不重复计数。MySQL/PostgreSQL 临时实例均已关闭，见[清理证据](evidence/id-implementation/cleanup.txt)。

这是功能与故障验证，不是吞吐/延迟验收，也不能证明无条件分布式唯一性。回绕及同秒复用用例故意复现重复，结果符合已批准边界。H2 仅用于事务与并行分配单测，不作为数据库兼容证据。

未覆盖：TiDB 真实实例、JDK 9～27 中其余运行版本、其他 Boot/JDK 组合、实际业务集群滚动发布、性能测试。高吞吐档 131,072 ID/s 是编码容量，10 万 TPS 未测。API 首版无批量接口。

架构复审：核心零框架依赖；单生产者拥有秒/序列；CAS 领取与 acquire/release 发布回收；外部状态只在适配层维护。padding 仅是布局提示，未测 false sharing 收益，也不宣称严格 lock-free。普通档数据库在线状态为弱状态；ZK 不逐秒写进度。

历史骨架结果保留在 [SCAFFOLD-GATE-RESULTS](SCAFFOLD-GATE-RESULTS.md)，不作为本轮功能证据。运行命令、版本矩阵日志及清理状态归档于 [证据说明](evidence/id-implementation/README.md)。
