# 性能验证政策

当前状态：`NOT_RUN`。只预留 `smartscity-id-benchmark` 模块，没有 JMH 依赖、Benchmark 源码或执行器。

`benchmarks` profile 仅用于隔离模块，默认关闭；对该 profile 执行 `validate` 不是性能实验。

未来实验需要明确授权，并预先记录场景、硬件、JDK/OS、线程数、批量大小、JVM 参数、预热/测量、重复次数、预计耗时与中止预算。输出吞吐、延迟及分配率，并保存原始报告。

优化结论需要可比较的 before/after；正确性优先，不为吞吐放宽唯一性或失败语义。理论位编码容量不等于实测发号吞吐，不在 README 声称未经验证的千万 TPS 或零分配。
