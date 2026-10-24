# Smartscity ID 审阅状态

- 2026-10-25：v0.8 方案方向获确认；v0.9 补充参数与六步实施顺序随后全部确认。
- 用户明确：“就按这个方案开始写代码，性能测试先不跑。”当前实施 IMPLEMENTED_UNRELEASED，正确性验证完成，Benchmark NOT_RUN。
- 基线：[方案](../proposals/id-v1/README.md)、[契约](../05-ID-Contract.md)、[配置](../06-Configuration.md)、[阶段](../phases/current.md)。
- 本轮不变更取模复用、运行期无 MySQL、ZK 不逐秒写进度及已披露的唯一性边界。
- 随后用户授权整体代码 review 并直接修复：完成时钟采样/锚点衔接、生产故障门禁、启动取消和有界资源清理修复，详见[复审记录](../../testing/ID-REVIEW.md)。性能仍未运行。
- 本轮交付范围已完成；TiDB 实例、其余 JDK/Boot 组合和性能保持 NOT_RUN，详见兼容矩阵。
- 执行证据见[门禁结果](../../testing/GATE-RESULTS.md)，不把方案确认当作测试通过。
