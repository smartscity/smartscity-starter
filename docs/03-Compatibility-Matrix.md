# 兼容矩阵

本轮构建使用 JDK 21.0.12.1、Maven 3.9.15；生产 JAR 为 Java 9 字节码。同一份生产制品用于以下运行检查，指纹见[验收记录](../testing/GATE-RESULTS.md)。

| 范围 | 实际版本 | 结果 |
|---|---|---|
| Core 独立运行 | JDK 17、21.0.12.1 | PASS，五种布局各生成/解析 64 个 ID |
| Starter / Actuator / Micrometer | Boot 2.7.18、3.3.6、3.3.13、3.4.13、3.5.16，JDK 21.0.12.1 | PASS |
| Starter / Actuator / Micrometer | Boot 4.0.9、4.1.2，JDK 21.0.12.1 | PASS，包含 Boot 4 健康接口适配 |
| MySQL | 9.6，Connector/J 8.4.0 | PASS，隔离真实实例 |
| PostgreSQL | 14.15，JDBC 42.7.4 | PASS，隔离真实实例 |
| ZooKeeper | 3.9.4 | PASS，临时真实服务器及 TCP 客户端 |
| TiDB | 6.5+ 为脚本要求 | NOT_RUN，未提供实例 |
| 其他 JDK / Boot 组合 | Core 目标 JDK 9～27 | NOT_RUN |

Boot 用例检查自动发现、开关、Bean 退让、真实 MySQL 发号，以及健康与指标；没有验证所有 Boot/JDK 组合。Java 9 字节码检查不等于已在每个 JDK 上运行。版本兼容也不等于官方仍维护该版本。

官方要求参考：[Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Boot 4.1](https://docs.spring.io/spring-boot/system-requirements.html)。本轮核查日期 2026-10-25；兼容结论以实际版本及日志为准。
