# 初次编译失败记录

新增 RestartDatabase 夹具误导入同包的 DriverDataSource，导致 testCompile 失败；Core 20 个用例已通过，适配层未运行。删除错误 import 后重新验证，见[最终结果](../id-restart-20261025-verified/README.md)。隔离 MySQL 已清理。
