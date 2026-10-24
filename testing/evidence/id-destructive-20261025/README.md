# 隔离故障证据

入口：`JAVA_HOME=<JDK21> python3 testing/run-destructive.py --run --output testing/evidence/id-destructive-20261025`。

自动创建私有临时目录及 127.0.0.1:13367 MySQL 9.6，使用新建 smartscity_id_test 库；结束关闭服务器并删除临时数据。每个 Maven 命令在 txt 首行，33 个故障组用例 + 1 个停机用例均通过，0 跳过。各类计数和摘要在 fault-reports/outage-reports，清理状态在 result.json。

新增 SIGSTOP/SIGCONT 真实进程暂停恢复；其余复用已实现测试，不冒充新增 34 个用例。源码指纹 sources.sha256。runner-guards.txt 是入口显式启用和端口保护的实际检查。
