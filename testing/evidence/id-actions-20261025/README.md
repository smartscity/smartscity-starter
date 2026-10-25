# GitHub Actions 接入验证（2026-10-25）

本轮只准备 workflow、验证入口和本地提交。**远程 Actions / Linux runner 实跑 / 完整性能矩阵：NOT_RUN**；未配置 git remote，未 push。以下不是新的吞吐测试结果。

| 检查 | 状态与证据 |
|---|---|
| workflow 语法、表达式、action 参数 | PASS：actionlint 1.7.12；见 static.txt。未安装 ShellCheck，Bash 另用 `bash -n` 检查 |
| JMH 构建 | PASS：JDK 21.0.12.1、Maven 3.9.15，见 build.txt；跳过功能测试，生产代码未改 |
| 双 JDK 发现 | PASS：17.0.0 / 21.0.12.1 执行 `java -jar … -l`，见 jmh-discovery-17.txt / jmh-discovery-21.txt；没有启动测量 fork |
| 结果校验与编排 | PASS：11 项 JSON 检查、7 个成功/失败分支、拒绝复用输出目录，见 orchestration.txt；全部是合成进程/数据 |
| 真实格式兼容 | PASS：解析已有 9 场 JMH、3 次定速历史文件，见 historical-parser.txt；未重跑旧实验 |

编排分支：正常完成、目标未达但允许记录、严格模式未达失败、Java 命令失败、超时退出码、缺失结果、重复 ID。验证 PARTIAL/MEASURED 区分、保留已完成的场景、退出码传递和 Summary 生成。超时分支使用合成退出码，不声称本机已验证 Linux GNU timeout 或 Actions 取消行为。

actionlint 发行压缩包的 SHA256 与官方 checksum 一致：`5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644`（darwin_amd64）。Actions 依赖固定到官方 API 核对的 commit SHA：checkout v4.2.2、setup-java v4.7.1、upload-artifact v4.6.2。

可重复验证入口：

```sh
./mvnw -Pbenchmarks -pl smartscity-id-benchmark -am -DskipTests package
bash -n testing/run-benchmark-ci.sh testing/check-benchmark-ci.sh
bash testing/check-benchmark-ci.sh
actionlint -shellcheck= .github/workflows/id-benchmark.yml
java -jar smartscity-id-benchmark/target/benchmarks.jar -l
```

归属复审：只增加 CI Pipeline 和结果校验；复用既有 Java 发号测量/工厂，生产模块和依赖不变。固定 runner 标签、全局串行、只读权限、不保留 checkout 凭据；失败归档使用本次运行专属目录。用法及预算统一由[性能说明](../../../docs/08-Benchmark.md#github-actions)维护。
