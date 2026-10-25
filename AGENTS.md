# Smartscity Starter Agent Guide

## 当前授权范围

2026-10-25 用户已确认方案、默认参数及实施顺序，并在实现与复审完成后授权本地提交、准备和执行隔离破坏测试与 benchmark。此前“性能测试先不跑”的限制已被本次明确授权替代。最新状态见 [审阅记录](docs/planning/review-state.md)。

- 只实现 ID 组件，不新增锁、缓存、消息、限流等其他组件。
- 遵循 [已确认方案](docs/proposals/id-v1/README.md) 和 [实施阶段](docs/phases/current.md)。
- 不把未运行的数据库、JDK/Boot 组合或理论容量当作已验证能力。
- 本次可提交到本地；不要 push、tag 或发布。
- 最新补测只做有限轮次重启/故障/并发验证；用户已排除持续运行测试。
- 后续已授权接入手动 GitHub Actions benchmark；本次准备配置、本地验证和提交，不 push 或触发远程运行。

## 修改前必读

1. 本文件和 [文档索引](docs/00-README.md)。
2. [架构与编码规范](docs/engineering/architecture-and-coding.md)及[设计模式策略](docs/engineering/design-pattern-policy.md)。
3. 相关已接受 ADR、领域 owning 文档、模块 README。
4. [测试清单](testing/TEST-INVENTORY.md)和当前阶段计划。

## Source of Truth

用户最新指令决定授权范围。工程约束由本文件和工程规范拥有；同一设计事实只维护一个权威来源。

领域事实优先级：已接受的 `docs/adr/*` → [ID 契约](docs/05-ID-Contract.md) → [兼容矩阵](docs/03-Compatibility-Matrix.md) → [模块边界](docs/04-Module-Boundaries.md) → 测试清单 → 源码。`PROPOSED` ADR、规划、历史报告不代表设计已批准或能力已实现。发生实质冲突时先澄清受影响设计，其他已授权工作继续。

## 文档规则

- `README.md` 使用英文，`README.zh-CN.md` 使用中文；正文按语言分开，结构保持一致。
- 首页只保留简介、架构、接入、规划和导航；细节链接到所属文档。
- 用最少的内容表达清楚；名称直观，关系和流程优先用 Mermaid。

## 工程规则

- 顺序：外部契约 → 验收门禁 → 模块/状态归属 → API 设计 → 实现 → 兼容性验证 → 按授权进行性能验证 → 文档闭环。
- 根 POM 不继承 Spring Boot Parent；BOM 只管理本项目生产组件，不接管 Spring、Jackson、Netty 等外部版本。
- `id-api` 零第三方依赖；`id-spi` 只允许依赖 API；`id-core` 只允许依赖 API、SPI 和 JDK。Spring 留在适配层。
- Starter 只聚合依赖；testkit、compatibility-tests、benchmark 不进入生产传递依赖。
- 对象必须有明确职责、状态 owner、生命周期及线程归属。避免无领域含义的 Manager、Helper、Utils、Common、Misc、Processor、Handler、Context。
- 生产代码不使用 Lombok、Unsafe、JDK 内部 API；API 与核心不使用反射。暂不引入 Multi-Release JAR 或 `module-info.java`。
- 实现不得用默认 Node ID 0、IP/MAC/Pod 名散列、无约束序列回绕或忽略回拨伪装唯一性保证。静态 Node ID 不等于已具备冲突检测能力。
- 公共 API、配置名、异常、位布局、Epoch、ArtifactId、支持矩阵和默认失败语义的冻结或变更，需要对应 ADR 与验收证据；当前基线见 ADR-0006 和 ID 契约。
- 保留用户及其他会话改动。Git 初始化、提交、tag、push 和发布按实际授权执行。

## 验证与交付

日常命令见 [README](README.md)。构建 JDK 与产物运行 JDK 分离。配置 `--release 9` 不等于通过 Java 9～27 运行验证。

当前必须执行实现正确性检查；不得仅凭构建成功声称唯一性、并发安全、Spring Boot 自动装配或性能通过。使用 `PASS`、`FAIL`、`NOT_RUN`、`PARTIAL`、`BLOCKED` 记录真实状态。

Benchmark 只在明确授权且有场景、预算和环境记录时运行，默认排除。性能结论需要可比较的 before/after、JDK、CPU、OS、线程数、批量大小、JVM 参数及分配率；理论编码容量不是实测吞吐。

交付前复审模块依赖、对象归属、模式取舍及证据。只有骨架被授权时，以骨架验收为停点；不进入功能实现。

<!-- CODEGRAPH_START -->
## CodeGraph

如果仓库根目录存在 `.codegraph/`，理解或定位代码时先使用 `codegraph_explore`，或 `codegraph explore "<symbol names or question>"`，再使用文本搜索或读取源码。没有 `.codegraph/` 时直接跳过；不自动创建索引。
<!-- CODEGRAPH_END -->
