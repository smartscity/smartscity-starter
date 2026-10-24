# Changelog

## Unreleased — 0.1.0-SNAPSHOT

- 建立工程规范、文档索引、模块边界、兼容性目标和验收记录入口。
- 创建 ID 组件分层、内部 BOM、独立测试及性能模块占位。
- 配置 Maven Wrapper、JDK 21 构建要求和 Java 9 编译目标。
- 实现三档 ID 位布局、单调时间、CAS RingBuffer、超时及失效停发。
- 实现 MySQL / PostgreSQL / TiDB 方言、启动自增 Worker 分配与退出弱注销；JavaScript 档增加 ZK 临时节点占用。
- 增加 Spring Boot 自动装配、健康与指标、DDL、配置校验、可运行示例及功能集成测试。
- 复审修复时钟采样误判与锚点衔接、生产故障停发、取消/异常资源清理、ZK 关闭预算及 SQL 原始异常保留。

当前未发布。实际验证组合与未执行项见 [门禁结果](testing/GATE-RESULTS.md)；按用户要求未运行性能测试。
