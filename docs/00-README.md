# 文档索引

当前阶段：已授权 ID 实现与正确性验收，性能测试暂停。`PROPOSED` 表示尚待设计确认；`NOT_RUN` 表示没有执行证据。

设计入口：[分布式唯一 ID 精简方案 v0.9](proposals/id-v1/README.md)（方案与实施步骤均已确认，已实现并记录本轮验证结果）。审阅进度见[设计审阅状态](planning/review-state.md)。旧领域 ADR 已标为被替代；工程规范继续有效。

| 文档 | 拥有的事实 |
|---|---|
| [项目范围](01-Project-Scope.md) | 当前产品边界 |
| [系统架构](02-System-Architecture.md) | 分层与组装关系 |
| [兼容矩阵](03-Compatibility-Matrix.md) | 目标范围与逐组合证据要求 |
| [模块边界](04-Module-Boundaries.md) | 模块、依赖与管理规则 |
| [ID 契约](05-ID-Contract.md) | 用户语义与不变量 |
| [配置](06-Configuration.md) | 配置命名空间与设计状态 |
| [测试与门禁](07-Testing-and-Gates.md) | 验收政策 |
| [Benchmark](08-Benchmark.md) | 性能实验授权与证据要求 |
| [发布政策](09-Release-Policy.md) | 版本与发布准备条件 |
| [ADR 索引](adr/README.md) | 决策记录与状态 |
| [架构规范](engineering/architecture-and-coding.md) | 对象与状态归属门禁 |
| [模式策略](engineering/design-pattern-policy.md) | 模式评估与复审 |
| [阶段计划](phases/current.md) | 本次授权、验收与停点 |
| [测试清单](../testing/TEST-INVENTORY.md) | 验证覆盖映射 |
| [门禁结果](../testing/GATE-RESULTS.md) | 本次实际检查证据 |

模块自身 README 说明本模块状态，并引用 owning 文档，不重复维护一份契约。
