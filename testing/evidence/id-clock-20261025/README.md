# 时间回拨专项证据

环境：2026-10-25，macOS 26.5.1 x86_64，Temurin JDK 21.0.12.1，Maven 3.9.15；隔离集成使用 MySQL 9.6、ZK 3.9.4。无宿主机改时、持续运行或 benchmark。

| 文件 | 证据 |
|---|---|
| before.txt | 修改生产代码前：16 个新增核心用例通过；7 个数据库校时用例中，极端值和 NULL 检查的 2 个用例失败，复现漏检 |
| after.txt | 修复后核心 33 + 适配层 18 = 51 个用例通过，0 失败/跳过 |
| integration/faults.txt | 全新隔离 MySQL/ZK 下，56 个故障与专项用例通过 |
| integration/outage.txt | 数据库停机用例 1 个通过 |
| integration/result.json | PASS，36.03 秒，cleanup=true；临时数据目录由执行器退出清理 |
| integration/*-reports/ | 各类 Surefire 摘要与计数 |
| package.txt、artifacts.txt | 生产模块打包；依赖边界、元数据、Java 9 字节码与 JAR 指纹 |
| sources.sha256 | 本轮核心/适配层源码及隔离执行器指纹 |

共 65 个不同用例通过：51 + 57 − 43 个重复用例。新增专项为 11 个 ClockRollbackTest、5 个 ClockRollbackConcurrencyTest、7 个 ClockValidationTest。

构建使用 `JAVA_HOME=/Users/liyunlong/.sdkman/candidates/java/21.0.12+1.1-tem`。复现命令：

```sh
./mvnw -o -B -pl smartscity-id-spring-boot-autoconfigure -am test \
  -Dtest=ClockRollbackTest,ClockRollbackConcurrencyTest,ClockValidationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

after.txt 在上述列表追加 `CoreTest,ClockAndLifecycleTest,LifecycleReviewTest,AutoConfigurationTest,JdbcIntegrationTest`。隔离执行命令：

```sh
python3 testing/run-destructive.py --run --output testing/evidence/id-clock-20261025/integration
./mvnw -o -B -pl smartscity-id-spring-boot-starter -am -DskipTests package
python3 testing/check-artifacts.py
```

再次执行时须将隔离输出目录改为新路径；其 faults.txt/outage.txt 首行保留实际 Maven 命令。打包步骤跳过测试，因为前两轮已执行；不将打包算作额外用例。覆盖边界见[报告](../../CLOCK-RESULTS.md)。
