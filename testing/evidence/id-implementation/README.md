# 本轮运行证据

日期：2026-10-25。工作区版本 0.1.0-SNAPSHOT，尚未提交；生产 JAR 指纹见 [artifacts](artifacts.txt)，源码/构建/资源指纹见 [source-sha256](source-sha256.txt)。后续只补充了测试与文档，生产代码未变。

环境：macOS 26.5.1 x86_64；Maven 3.9.15；Temurin JDK 21.0.12.1，另以 JDK 17 执行纯 Core 探针。隔离 MySQL 9.6.0 / Connector-J 8.4.0；PostgreSQL 14.15 / JDBC 42.7.4；ZK 3.9.4 临时服务器及真实 TCP 客户端。

## 复现入口

使用仓库 Wrapper 与 JDK 21。仅向本轮隔离数据库传入 URL；测试库用户名与驱动配置见对应测试，不能使用业务数据库。

```sh
./mvnw -Pverification clean install \
  -Did.test.mysql.url='jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true' \
  -Did.test.postgres.url='jdbc:postgresql://127.0.0.1:15437/smartscity_id_test'
./mvnw -pl smartscity-id-core -am test
./mvnw -pl smartscity-id-spring-boot-autoconfigure -am test \
  -Dtest=ZookeeperIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Did.test.mysql.url='jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true'
./mvnw -f smartscity-id-compatibility-tests/pom.xml test \
  -Dspring-boot.version=<版本> \
  -Did.test.mysql.url='jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true'
python3 testing/check-artifacts.py
testing/run-runtime-probe.sh <JDK17路径> <JDK21路径>
```

以上为复现入口；本轮 Maven 下载使用了连接/请求超时，依赖齐全后的运行加 `-o -B -ntp`。示例启动步骤见 [samples](../../../samples/README.md)。停机用例必须在其他 MySQL 检查完成后单独运行，将直接关闭隔离 MySQL：

```sh
./mvnw -pl smartscity-id-spring-boot-autoconfigure -am test \
  -Dtest=MySqlOutageTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Did.test.mysql.outage=true \
  -Did.test.mysql.url='jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true'
```

[停机测试日志](mysql-outage.txt)已通过。

## 日志

- [完整 reactor](full-build.txt)，随后新增核心边界用例见 [core-final](core-final.txt)。
- [完整 JavaScript SDK 集成](zookeeper-full-sdk.txt)。
- Boot：[3.3.6](boot-3.3.6.txt)、[3.3.13](boot-3.3.13.txt)、[3.4.13](boot-3.4.13.txt)、[3.5.16](boot-3.5.16.txt)、[4.0.9](boot-4.0.9.txt)、[4.1.2](boot-4.1.2.txt)；2.7.18 在 reactor 中执行。
- [Core 运行](runtime-probe.txt)、[产物检查](artifacts.txt)、[示例构建](example-build.txt)、[示例运行](example-run.txt)。

测试过程中发现并修正过：Boot 测试依赖需匹配各自 BOM；子进程结果必须原子发布；ZK 断连恢复用例需要给同会话重连保留时间，强制过期另测。此处保存修正后的实际运行日志，旧失败记录不计为通过。

清理状态：PASS。[服务器关闭与端口检查](cleanup.txt)确认本轮 MySQL/PostgreSQL 隔离实例均已停止；ZK 与测试子进程由测试释放。未运行 Benchmark。
