# 配置

前缀 `smartscity.id`，完整字段元数据由编译生成。

| 配置 | 默认 | 说明 |
|---|---|---|
| enabled | true | false 时不分配 Worker |
| profile | standard | standard / javascript / high-throughput |
| group | default | 1～64 位字母、数字、下划线或短横线 |
| javascript-sequence-bits | 15 | 15～17，时间位自动等量减少 |
| wait-timeout | 10ms | 0～1s，0 表示立即失败 |
| buffer-size / low-water-percent | 16384 / 50 | 容量为 2 的幂，水位 1～99 |
| clock-tolerance | 1s | 正值；超差终止本实例发号 |
| startup-timeout / shutdown-timeout | 10s / 2s | 有限启动、ZK 与数据库共用外部释放预算 |
| worker-attempts | 1024 | 启动占位冲突的最多尝试次数 |
| database.type | mysql | mysql / tidb / postgresql |
| database.url / username / password | 无 | 独立连接；无 URL 时使用唯一或 primary DataSource |
| database.driver-class-name | 无 | 通常 JDBC 自动发现即可，驱动由应用提供 |
| zookeeper.connect-string | 无 | javascript 档必需，应用提供 ZK 客户端依赖 |
| zookeeper.root / session-timeout | /smartscity-id / 15s | 同组各实例必须一致，服务端可协商会话时间 |
| zookeeper.auth-scheme / auth | 无 | 成对设置；无认证仅适用于隔离/受控环境 |

Epoch 固定 2026-01-01 UTC，不提供运行期切换。数据库表由部署方执行制品内 `db/{mysql,tidb,postgresql}.sql` 建立，不自动建表。TiDB 脚本使用 AUTO_ID_CACHE=1，要求 TiDB 6.5+；真实兼容状态见验收记录。

自建连接无连接池后台查询；业务提供的 DataSource 生命周期仍由业务容器拥有。JDBC 超时之外还有调用方启动/关闭预算，忽略取消的第三方驱动可能延迟释放资源；超时启动结果不会对外启用。

数据库校时和发号共用一次时间锚点；不在校验后重新取墙钟锚定。内部时钟采样跨度超过 1ms 时丢弃并重试，最多三次；持续无法稳定采样时按时钟异常停发，墙钟偏差阈值仍由 `clock-tolerance` 控制。

使用 Actuator 时，将 `smartscityId` 加入 readiness health group；未接入 Actuator 可直接读取 `snapshot().isReady()`。数据库原始 ID、Worker、认证数据不作为指标标签。
