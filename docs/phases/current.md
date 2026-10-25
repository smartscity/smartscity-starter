# 当前阶段：GitHub Actions benchmark 接入

2026-10-25 用户批准接入手动 JMH workflow。时间回拨专项和有限轮次重启补测已完成。本轮准备配置、验证并本地提交，不 push、不触发远程运行、不做持续运行测试。

范围：JDK 21 构建，JDK 17/21 串行测量，三种布局 × 1/4/8 线程，有限时长定速检查，托管/自托管选择，超时与失败归档。生产代码不变，预算及统计口径由[性能文档](../08-Benchmark.md#github-actions)拥有。

职责：workflow 拥有 job 和归档；Bash 入口拥有有界子进程、输出目录和测量状态；jq 校验结果；原 Java BenchmarkGenerator/JMH/FixedRateProbe 拥有 SDK 和测量线程。复用 Factory 和顺序 Pipeline，不新增生产抽象或重复实现发号逻辑。

验收：workflow 静态检查、JMH 构建和双 JDK 发现、结果校验及编排成功/失败场景；实际 Actions 运行标记 NOT_RUN，不把合成检查当作性能结果。完成后文档闭环并本地提交。此前记录：[时间回拨](../../testing/CLOCK-RESULTS.md)、[重启稳定性](../../testing/RESTART-RESULTS.md)、[性能与故障实验](../../testing/EXPERIMENT-RESULTS.md)。
