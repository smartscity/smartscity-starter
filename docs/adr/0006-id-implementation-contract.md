# ADR 0006 — ID 首版实现契约

状态：IMPLEMENTED_UNRELEASED。依据：2026-10-25 用户批准 v0.9 默认参数及实施顺序，并授权编码、排除性能测试。

公共入口采用 `IdGenerator` 的单次发号、解析、只读快照与幂等关闭；异常使用固定 Reason，具体语义由[契约](../05-ID-Contract.md)拥有。配置键由[配置](../06-Configuration.md)拥有，布局及 Epoch 由[已批准方案](../proposals/id-v1/README.md)拥有。

采用一个 AutoConfigure 制品，Boot 2/3 与 Boot 4 健康接口用条件隔离的适配类处理；用户 Boot BOM 管理外部依赖，Core 仍零框架依赖。当前版本未发布，不声明未执行的兼容组合或性能结果。[验收证据](../../testing/GATE-RESULTS.md)。

2026-10-25 复审修复：数据库校验与 Core 共用 MonotonicClock；Core 增加 usingClock 组装入口，IdGenerator 用户接口不变。取消和异常清理保留原始原因及中断；外部释放共用预算。布局与唯一性边界不变，详见[复审记录](../../testing/ID-REVIEW.md)。

同日时间回拨专项：启动校时拒绝 SQL NULL，时间差使用精确算术，溢出按 CLOCK 拒绝；修正既有校时检查，不变更 API、配置、布局或默认失败策略。[专项证据](../../testing/CLOCK-RESULTS.md)。
