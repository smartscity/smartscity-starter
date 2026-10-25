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

## GitHub Actions

[ID benchmark workflow](../.github/workflows/id-benchmark.yml) 仅允许手动触发，不随 push/PR 或定时自动运行。workflow 进入仓库默认分支后，在 **Actions → ID benchmark → Run workflow** 选择执行分支和参数（[GitHub 手动触发说明](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)）。本地准备完成不代表已推送或远程执行。

| 参数 | 默认值及含义 |
|---|---|
| `runner` | `github-hosted` 使用 Ubuntu 24.04；`self-hosted` 使用带 `self-hosted, linux, x64, id-benchmark` 标签的机器 |
| `require_100k` | `false`：记录 MET/NOT_MET，正常完成测量即可；`true`：三次定速检查必须全部达标，否则 job 失败 |

- JDK 21 构建，各用 Temurin JDK 17/21 运行。两个 job 串行，每个 job 包含上述 9 场 JMH 和 3 次定速检查；每场参数、512 MiB 测量堆及生产默认配置不变。
- 每个 JDK 测量预算 480 秒，单场上限 40 秒；测量 step 上限 10 分钟，含构建的 job 上限 25 分钟。预计总测量 8～10 分钟，依赖/JDK 下载另计。仓库内同 workflow 不并发；仍需避免自托管机器上的其他负载。
- Java 负责测量，Bash 负责有界进程调度，jq 验证及汇总结果；不使用 Python 执行本 CI 入口。复用现有 BenchmarkGenerator 工厂，不改变 Core/生产依赖。
- Summary 展示成功 ID/s、超时/s、分配量及定速 p99、missed/late/重复数；Artifacts 保存每场原始 JSON、日志、CPU/OS/JDK、源码/JAR 指纹和构建日志，保留 30 天。
- 缺失指标、错误场景、重复 ID、命令失败或超时均失败，已有测量标记 PARTIAL；校验通过的完整执行标记 MEASURED，**不等于 10 万 TPS 达标**。取消时尽力保留部分结果，强制停止或 runner 丢失不保证上传。

自托管机须预装 Bash、Git、jq、GNU coreutils（timeout/sha256sum）、util-linux（lscpu），并能运行所固定 Actions 版本及下载 Temurin/Maven 依赖。使用独占、空闲的测试机器；无需 MySQL/ZK。GitHub 托管结果用于观察趋势，生产容量与版本对比使用固定机器、相同参数，并保留 before/after 证据。[Runner 类型](https://docs.github.com/en/actions/how-tos/write-workflows/choose-where-workflows-run/choose-the-runner-for-a-job)。

本地 Linux 也可执行相同入口（先完成上述 JDK 21 构建，再切换所需运行 JDK）：

```sh
REQUIRE_100K=false bash testing/run-benchmark-ci.sh /tmp/id-ci-result
```

输出目录须不存在。编排及结果检查可用 `bash testing/check-benchmark-ci.sh` 验证；其中使用合成进程和结果，不产生性能结论。当前验证状态见[Actions 接入记录](../testing/evidence/id-actions-20261025/README.md)。

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
