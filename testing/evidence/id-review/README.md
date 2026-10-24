# 代码复审运行证据

2026-10-25，0.1.0-SNAPSHOT 工作区，未提交。[源码/构建/资源 SHA-256](source-sha256.txt)、[生产 JAR SHA-256](artifacts.txt)。

## 执行与结果

- 修复前：[核心 4 个失败](before-core.txt)、[适配层 5 个失败](before-adapters.txt)。失败涵盖时钟采样、构造/生产异常、取消清理、SQL 异常保留以及 ZK 无响应/中断关闭。
- [完整 install](full-build.txt)：17 个 Core、23 个适配层、3 个 Starter 用例通过，停机用例单独执行。开始时已执行 clean；新增测试直接访问 ZK 受保护方法的编译问题修正后完成构建，生产代码没有因此改动。
- [停机用例](outage.txt)：1 个通过，另补 [ZK 拒绝占位的已提交 Worker 清理](zk-rejected-claim.txt) 1 个通过，合计 45 个不同用例。
- Boot：[3.3.6](boot-3.3.6.txt)、[3.3.13](boot-3.3.13.txt)、[3.4.13](boot-3.4.13.txt)、[3.5.16](boot-3.5.16.txt)、[4.0.9](boot-4.0.9.txt)、[4.1.2](boot-4.1.2.txt)；2.7.18 在完整构建中验证。每档 3 个用例，使用同一生产制品。
- [JDK 17/21 Core 探针](runtime.txt)、[制品检查](artifacts.txt)、[示例运行](example-run.txt)。
- [隔离数据库清理](cleanup.txt)。临时 ZK 和子进程由测试释放；无性能测试。

## 复现命令

JDK 21，依赖已缓存时可用 `-o -B -ntp`。数据库必须为隔离测试实例，用户名与驱动见测试代码；不能指向业务数据库。

```sh
./mvnw -Pverification clean install \
  -Did.test.mysql.url='jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true' \
  -Did.test.postgres.url='jdbc:postgresql://127.0.0.1:15437/smartscity_id_test'
./mvnw -f smartscity-id-compatibility-tests/pom.xml test -Dspring-boot.version=<版本> \
  -Did.test.mysql.url='jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true'
testing/run-runtime-probe.sh <JDK17路径> <JDK21路径>
python3 testing/check-artifacts.py
```

停机用例最后执行，直接关闭本轮 13367 端口 MySQL：

```sh
./mvnw -pl smartscity-id-spring-boot-autoconfigure -am test \
  -Dtest=MySqlOutageTest -Dsurefire.failIfNoSpecifiedTests=false -Did.test.mysql.outage=true \
  -Did.test.mysql.url='jdbc:mysql://127.0.0.1:13367/smartscity_id_test?useSSL=false&allowPublicKeyRetrieval=true'
```
