# ADR 索引

状态：`ACCEPTED_FOR_SCAFFOLD` 只用于已落入本次骨架的工程选择；`SUPERSEDED` 表示早期候选已由当前方案取代。设计确认不授权实现。

| ADR | 主题 | 状态 |
|---|---|---|
| [0001](0001-java-9-bytecode-baseline.md) | JDK 21 构建、Java 9 release 目标 | ACCEPTED_FOR_SCAFFOLD |
| [0002](0002-spring-boot-compatibility-policy.md) | 兼容声明以实际证据为准 | ACCEPTED_FOR_SCAFFOLD |
| [0003](0003-id-layout-v1.md) | ID 位布局与 Epoch | SUPERSEDED |
| [0004](0004-node-id-allocation.md) | Worker 分配 | SUPERSEDED |
| [0005](0005-clock-rollback-policy.md) | 时间回拨与等待 | SUPERSEDED |
| [0006](0006-id-implementation-contract.md) | 首版 API、配置及代际健康适配 | IMPLEMENTED_UNRELEASED |

骨架工程选择依据本次用户提供的方案及明确的创建骨架指令，领域方向的确认与实施授权见[审阅状态](../planning/review-state.md)。
