# 最小接入示例

先执行根工程 `./mvnw -Pverification install`，再执行：

```sh
./mvnw -f samples/id-example/pom.xml package
ID_DATABASE_URL='jdbc:mysql://localhost:3306/id' ID_DATABASE_USER=id \
  java -jar samples/id-example/target/smartscity-id-example-0.1.0-SNAPSHOT.jar
```

先在专用数据库执行 `smartscity-id-spring-boot-autoconfigure/src/main/resources/db/mysql.sql`。密码通过环境变量 `ID_DATABASE_PASSWORD` 传入，不写进源码或命令日志。程序启动申请 Worker、生成一个 ID、打印解析结果并正常关闭。
