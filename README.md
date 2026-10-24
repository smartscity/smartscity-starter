# Smartscity Starter · Distributed ID

秒级 Snowflake、启动自增 Worker、本地单调时间与 CAS RingBuffer。当前只包含 ID 组件。

- 普通：30/18/15；JavaScript：28/10/15，可减少时间位扩大序列；高吞吐：30/16/17。
- Epoch：2026-01-01 UTC。默认等待 10ms，缓存 16,384，低水位 50%。
- 数据库只在启动和退出时访问；JavaScript 档另维护 ZK Worker 占用和会话，不逐秒写进度。
- 大 Worker 空间和 ZK 占位均有部署前提，保证边界见[方案](docs/proposals/id-v1/README.md)。编码容量不是实测吞吐。

## 接入

应用使用自己的 Spring Boot BOM，添加 `com.smartscity:smartscity-id-spring-boot-starter:0.1.0-SNAPSHOT` 和所选 JDBC 驱动。JavaScript 档另加 `org.apache.zookeeper:zookeeper`（本轮测试版本 3.9.4）。

执行制品中的 `db/mysql.sql`、`db/postgresql.sql` 或 `db/tidb.sql` 后配置：

```yaml
smartscity:
  id:
    group: orders
    profile: standard
    database:
      type: mysql
      url: ${ID_DATABASE_URL}
      username: ${ID_DATABASE_USER}
      password: ${ID_DATABASE_PASSWORD}
```

注入 `IdGenerator`，调用 `getUniqueId()`；自定义该 Bean 时自动配置退让。已有唯一或 primary DataSource 时可省略 URL。JavaScript 档增加 `zookeeper.connect-string`；均衡流量并预留 Worker 发布余量。

启用 Actuator 时，将 `smartscityId` 加入 readiness health group；Micrometer 存在时提供 `smartscity.id.*` 指标。完整[配置](docs/06-Configuration.md)、[API 契约](docs/05-ID-Contract.md)、[可运行示例](samples/README.md)。

## 构建与验证

使用 JDK 21 构建，生产字节码目标 Java 9：

```sh
./mvnw -Pverification clean verify
```

真实 MySQL 测试需显式传入 `-Did.test.mysql.url=jdbc:mysql://127.0.0.1:13367/smartscity_id_test`，仅使用隔离测试库。缺环境时报告跳过，不能视为通过。ZK 测试启动临时本地服务器。性能测试默认排除；显式执行命令和预算见[实验说明](docs/08-Benchmark.md)。隔离破坏测试：`python3 testing/run-destructive.py --run --output /tmp/id-fault-result`（需要 MySQL 本地二进制，自动创建并清理独立实例）。

有限轮次重启测试使用 Java/JUnit，覆盖 1000 次 SDK 创建/关闭、20 轮真实进程替换及 ZK 抖动；入口见[重启稳定性说明](testing/RESTART-RESULTS.md)。默认不运行这些循环。

[验收证据](testing/GATE-RESULTS.md)和[兼容矩阵](docs/03-Compatibility-Matrix.md)说明实际已验证范围；不承诺未运行组合。项目许可证尚待选择；已本地提交，未推送或发布。
