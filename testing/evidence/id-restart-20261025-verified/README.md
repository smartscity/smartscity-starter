# 最终稳定性验证

命令：`JAVA_HOME=<JDK21> python3 testing/run-destructive.py --run --restart --output testing/evidence/id-restart-20261025-verified`。

macOS 26.5.1 x86_64、Temurin 21.0.12.1、MySQL 9.6、ZK 3.9.4。Maven 主 JVM 测试堆 512 MiB，SDK 子 JVM 128 MiB。新增 8 个用例加原故障套件，共 42 个用例通过、0 失败、0 跳过，耗时 101.62 秒。

faults.txt / outage.txt 首行保存实际 Maven 命令；逐类摘要和计数在 fault-reports / outage-reports。result.json 记录清理状态；sources.sha256 对应最终源码。report-guard.txt 为报告器遗漏/空套件保护的独立检查，与发号功能测试分开计数。

前一次完整执行（id-restart-20261025-final）已通过 42 个用例；最终复审将 ZK 拒绝计数限定为 COORDINATION 后，本目录重新执行确认。最初 id-restart-20261025 的夹具导入错误导致编译失败，已修正并保留原始记录。
