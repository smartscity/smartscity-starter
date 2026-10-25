# 测试清单

本表是覆盖映射，实际通过/跳过见[GATE-RESULTS](GATE-RESULTS.md)。

| 范围 | 可执行入口 |
|---|---|
| 编码、时间、容量、CAS、并发、超时、中断、关闭 | CoreTest |
| 采样期间暂停、共用校时锚点、生产故障、初始化清理及并发关闭 | ClockAndLifecycleTest |
| 累计回拨、采样重试、nanoTime 跨界、满/空缓存、并发终止及编码边界 | ClockRollbackTest、ClockRollbackConcurrencyTest（可控时钟，[专项报告](CLOCK-RESULTS.md)） |
| 数据库校时偏差/溢出/NULL、慢查询及失败清理 | ClockValidationTest（合成 JDBC 响应，非真实数据库兼容证明） |
| JDBC 设置/回滚异常、启动 Error、取消后迟到驱动的资源释放 | LifecycleReviewTest（H2 与故障代理，仅证明生命周期行为） |
| 配置、Bean 退让、健康与指标 | AutoConfigurationTest |
| JDBC 事务与并行分配的进程内检查 | JdbcIntegrationTest（H2，不作为数据库兼容证明） |
| MySQL 校时/自增/冲突、零运行期数据库连接、多进程、kill -9 与 SIGSTOP/SIGCONT 暂停恢复 | MySqlIntegrationTest（显式 URL） |
| PostgreSQL identity/校时/冲突/退出 | PostgresIntegrationTest（显式 URL） |
| ZK 原子占用、复用、断连恢复、服务器会话过期、完整 SDK 缓存停发、无响应关闭与中断保留 | ZookeeperIntegrationTest（临时真实服务器与 TCP 客户端；完整 SDK 用例另需 MySQL URL） |
| 真数据库停机后已启动 SDK 继续发号、新启动失败 | MySqlOutageTest（另加显式破坏性测试开关，仅限本轮隔离端口） |
| 自动发现、禁用、用户 Bean、真实 Starter 发号及可选健康/指标 | StarterDiscoveryTest（独立 Boot BOM） |
| 用户接入路径 | samples/id-example 的实际启动 |
| 未覆盖目标 | TiDB 实例、未安装 JDK/Boot 组合保持 NOT_RUN |

隔离故障统一入口：`python3 testing/run-destructive.py --run --output <新目录>`；新建私有 MySQL、真实子进程和 ZK TCP，覆盖时钟、关闭、取消、失联、过期、停机。时钟异常通过 TimeSource 注入，不修改宿主机时间；进程 SIGSTOP 不等于整机休眠验证。

性能入口、预算与口径见[实验说明](../docs/08-Benchmark.md)。

Actions 编排/结果校验：`bash testing/check-benchmark-ci.sh`（先构建 benchmark JAR），使用合成进程验证正常、失败、缺失、重复、超时及严格目标分支；不是性能测试。workflow 语法使用 actionlint；远程执行证据单独记录。

有限轮次补测（`id.test.restart=true`）：RestartConcurrencyTest、RestartStabilityTest、ZookeeperRestartStabilityTest。内容与实际结果见[报告](RESTART-RESULTS.md)；不包含用户已排除的持续运行测试。
