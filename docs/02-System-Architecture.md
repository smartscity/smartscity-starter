# 系统架构

`Starter → AutoConfigure → Core → SPI/API`。API 零依赖，Core 只有 API/SPI/JDK；JDBC、ZK、Spring、Micrometer 留在适配层。

| 对象 | 状态与线程归属 |
|---|---|
| BitsAllocator / GeneratorOptions | 不可变布局、默认值和范围校验 |
| MonotonicClock | 一次墙钟/nanoTime 锚点，启动校验与发号共用；有界采样、并发读取 |
| DefaultUniqueIdGenerator | 秒/序列，仅缓存生产者推进 |
| RingBuffer | 槽位轮次和 CAS 消费游标；单生产、多消费 |
| CachedUniqueIdGenerator | 缓存、单生产线程、停发状态、关闭；并发入口 |
| GeneratorMetrics | 有界计数与延迟桶，快照只读 |
| JdbcWorkerRepository | 启动事务、校时、自增和退出更新，无后台查询 |
| ZookeeperWorkerOccupancy | 会话、节点及状态回调；本地状态供发号线程读取 |
| WorkerAllocation / ManagedIdGenerator | 外部释放共用预算、尽力退出更新、每实例 shutdown hook；取消不丢弃清理 |
| IdGeneratorFactory | 有界启动和组装，失败回收；不持有发号算法 |

采用 Builder 集中参数校验、Adapter 隔离数据库/ZK、Factory 组装资源；生命周期使用少量集中状态，不增加 State 子类或通用事件总线。Starter 只聚合，测试与性能模块不进入生产传递依赖。
