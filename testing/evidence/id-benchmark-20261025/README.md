# 性能原始证据

主计划：9 个 JMH 场景 + 3 轮高吞吐定速，每个进程命令均在对应 txt 首行。`result.json` 记录主计划完整结果。JavaScript 8 线程短窗口偏高，追加 `javascript-8-long` 一个 30 秒窗口，参数见原始日志；不改写主计划数据。

`sources.sha256` 为本轮执行前的 Java/POM 与 runner 指纹；`environment.json` 包含 JDK、硬件和实际 benchmark JAR SHA。生产 API/SPI/Core JAR 与 b7685f8 检查点一致。`build-initial-failed.txt` 保留首次并行清理导致的构建失败，`build.txt` 为顺序构建成功证据。

主计划 208.82 秒，补测约 36 秒。解读和限制见[报告](../../EXPERIMENT-RESULTS.md)。
