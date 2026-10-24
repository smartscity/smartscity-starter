# 性能实验

2026-10-25 用户授权准备并执行。本模块默认排除，普通构建不会启动负载；本地结果见[实验报告](../testing/EXPERIMENT-RESULTS.md)。不用于宣称生产性能或优化收益，没有 before/after 对照。

## 运行

使用 JDK 21，先构建，再执行；不要与其他 Maven `clean` 共用输出目录并行运行：

```sh
./mvnw -Pbenchmarks -pl smartscity-id-benchmark -am -DskipTests package
python3 testing/run-benchmarks.py --run --output /tmp/id-benchmark-result
```

输出目录必须不存在。保存每场 JMH 原始 JSON、日志、成功/失败分项、GC 分配率、环境、源码及 JAR SHA-256。测试独立运行，不访问业务数据库。

## 本轮预算

- 环境：i7-9750H，12 逻辑核，16 GiB，macOS 26.5.1，Temurin 21.0.12.1；本地存储，发号测量无磁盘 I/O。
- JMH 1.37：普通 / JavaScript / 高吞吐 × 1 / 4 / 8 消费线程，共 9 场。每场一个 SDK、单次调用、不批量；预热 2×2 秒，测量 3×3 秒，1 个独立 fork。
- JVM：测量进程 `-Xms512m -Xmx512m`；默认缓存 16384、低水位 50%、等待 10ms、时钟阈值 1s，保留完整指标与校时逻辑。
- 定速：高吞吐档，4 消费线程，目标 100000 次/秒；每轮预热 5 秒、测量 20 秒，3 个独立进程。记录逐秒成功量、调用延迟和从计划到达到完成的延迟；全部成功 ID 排序查重。
- 预计 4～5 分钟；脚本总上限 480 秒，JMH 每场 40 秒、定速每轮 40 秒。构建下载不计入执行预算。
- 重复 ID、非 TIMEOUT 发号错误、子进程失败或超预算立即中止，保留 PARTIAL 证据；超时请求如实计数，不伪装成功。

## 统计口径

JMH 主指标是调用次数；`ids` 才是成功 ID/s，`timeouts` 是超时/s。`gc.alloc.rate.norm` 是字节/调用，包含失败路径，不能当作字节/成功 ID。计数采用 [OpenJDK JMH 辅助计数器](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_23_AuxCounters.java) 的分项统计方式。

定速按固定到达时间分配请求，迟到超过 10ms 不积压，记为 missed；窗口后返回记为 late，均不计入达成吞吐。`scheduled = successful + timeouts + missed + late`。调用延迟只统计返回 ID 的调用，另报计划到达延迟及全部拒绝/丢失量，避免掩盖负载发生器跟不上。所有计划请求均在窗口内成功且无重复才标记 targetMet。

两类测试均用本地 ACTIVE Worker 替身隔离发号开销：不包含数据库启动、ZK 会话流量或业务请求栈，不能证明端到端服务 TPS。JS 的真实占用和失联行为由[隔离故障测试](../testing/run-destructive.py)验证。笔记本调度、节能及后台应用可能影响结果。

## 对象与模式

| 问题 | 采用方式 | 状态与清理 |
|---|---|---|
| 重复配置组装 | BenchmarkGenerator 工厂复用生产 Builder | 只创建测试 Worker；generator 由 trial/probe 关闭 |
| 共享 SDK、独立统计 | JMH Benchmark/Thread State | 一个 SDK 多消费者；每线程拥有分项计数 |
| 定速与查重 | 独立 FixedRateProbe，不修改生产热路径 | 每消费者独占每四个数组槽位，结束汇总排序；有界数组和线程池 |
| 子进程隔离及超时 | Python 进程编排 | runner 拥有进程组、输出和预算；异常/超时终止进程组 |

本轮补测：发现 JS / 8 线程短窗口首轮偏高后，追加同一参数、1×5 秒预热及1×30 秒测量；结果见报告，总执行仍在预算内。
