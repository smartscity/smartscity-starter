# ID 验收记录

最新补测见[时间回拨专项](CLOCK-RESULTS.md)；此前有限轮次稳定性见[重启报告](RESTART-RESULTS.md)，均不包含持续运行。

实现和 review 修复已提交 `b7685f8`；按后续授权增加隔离破坏测试及 benchmark，见[本轮实验报告](EXPERIMENT-RESULTS.md)。以下保留实现/review 检查点原始范围。

## 实现与复审检查点

2026-10-25。已完成整体复审并修复 6 类问题，详见[复审记录](ID-REVIEW.md)。**45 个不同功能用例通过**，Boot 矩阵重复运行不重复计数；性能测试 **NOT_RUN**。

| 检查 | 本轮结果 |
|---|---|
| 问题复现 | 修复前 9 个用例失败：[核心](evidence/id-review/before-core.txt)、[适配层](evidence/id-review/before-adapters.txt) |
| 核心及完整构建 | PASS：[install 日志](evidence/id-review/full-build.txt)，Core 17 / 适配层 23 / Starter 3 个用例；唯一跳过的停机用例随后单独通过 |
| 外部生命周期 | PASS：真实 MySQL 9.6.0 / PostgreSQL 14.15 / ZK 3.9.4；包含多进程、kill -9、完整 JS SDK、ZK 无响应关闭及客户端线程实际退出 |
| ZK 占位被拒绝 | PASS：[已提交 Worker 在失败启动后注销](evidence/id-review/zk-rejected-claim.txt)，真实 ZK + H2 生命周期夹具 |
| 数据库停机 | PASS：[MySQL 关闭后原 SDK 继续发号、新 SDK 启动失败](evidence/id-review/outage.txt) |
| Boot 集成 | PASS：2.7.18、3.3.6、3.3.13、3.4.13、3.5.16、4.0.9、4.1.2，均在 JDK 21.0.12.1 上运行 |
| Core 运行 | PASS：[同一制品在 JDK 17 / 21 运行](evidence/id-review/runtime.txt) |
| 生产边界 | PASS：[Java 9 字节码、依赖、生成元数据与 JAR 指纹](evidence/id-review/artifacts.txt) |
| 用户示例 | PASS：[构建](evidence/id-review/example-build.txt)、[实际启动发号及关闭](evidence/id-review/example-run.txt) |

运行环境：macOS 26.5.1 x86_64，Maven 3.9.15，Temurin JDK 21.0.12.1。源码、命令和日志索引见[本轮证据](evidence/id-review/README.md)；隔离数据库已关闭，见[清理记录](evidence/id-review/cleanup.txt)。

仍未验证：TiDB 真实实例、其余 JDK / Boot 组合、业务集群滚动发布、吞吐及延迟。高吞吐档的 131,072 ID/s 为编码容量，不能作为 10 万 TPS 实测结果。普通档跨圈、JS 同秒复用及过期旧进程的风险维持已批准边界。

本轮无性能测试、提交、推送或发布。上一轮记录保留在[初次实现验收](ID-INITIAL-GATE-RESULTS.md)，其日志和指纹不冒充本轮制品证据。
