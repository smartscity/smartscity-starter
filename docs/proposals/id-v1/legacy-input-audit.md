# 参考材料核验与关键路径静态审计 — 2026-10-24

状态：`SOURCE_DOWNLOADED / STATIC_REVIEW_COMPLETE`（发号、Worker、预填充及装配关键路径）；构建、运行复现、故障验收和 Benchmark 均 `NOT_RUN`。这不是旧工程完整发布审计，也不代表线上版本已经发生下列故障。

## 1. 来源和下载完整性

前次提供的 `/Users/liyunlong/Downloads/huobi-starter-global-id` 只有 28 个子目录和 4 个 `.DS_Store`；`/Users/liyunlong/Downloads/global-id-example` 有 2 个空子目录、无文件。用户已确认这两个路径没有源码，保留此前核验结论。

本次从用户随后提供的 iCloud 分享下载 **longhub-framework-unique-id-starter**。它是本次实际审阅的参考版本，不能未经比对认定为 huobi 目录或八年前线上制品的精确副本。

- 本地目录：`/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter`。
- 文件夹枚举 38 个（含根），下载 48/48 文件，失败 0；总内容 143,908 bytes。
- 含 38 个 Java 文件、4 个 POM、4 个 IDEA 模块文件和 2 个 YAML；已逐文件检查下载长度并记录 SHA-256。
- [文件指纹清单](reference-manifest.json)只记录相对路径、长度和哈希；原始源码/配置及会话凭据不进入当前工程。
- [本地压缩包](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter.zip)，SHA-256：`23ba55ad7acd992242dda3b37f91deae74d6acd1fe87235ec0380e35e9180c0b`。这是下载后本地打包，不是服务端发布制品。
- 没有 `.codegraph/`，按指令跳过 CodeGraph；没有执行下载代码、Maven、旧应用或测试。
- iCloud 会话仅用于该目录的元数据/内容读取；没有修改云端文件或分享权限。临时参考目录不保证长期保留，文件指纹用于以后重新核对版本。

## 2. 已确认的实现结构

根 POM 聚合 `base / sdk / spi`，Java 编译设置为 8；父工程 `longhub-framework-starters:2.0.3.1-RELEASE` 不在分享目录内。SPI 模块有 POM/IDE 文件但没有 Java 实现。SDK 的 test 源码包含启动类、三套业务生成器配置及 HTTP 示例，不把这些示例视为并发正确性或压测证据。

主调用链：SDK 配置 → Druid/MyBatis Worker 插入 → 可选 ZooKeeper 注册 → CachedUniqueIdGenerator → RingBuffer.take；后台 BufferPaddingExecutor → nextIdsForOneSecond → RingBuffer.put。

SDK 默认布局 **30 位秒时间 / 18 位 Worker / 15 位序列**，Epoch 字符串 `2021-05-31`，boost=3、padding=65%、schedule=60s。基础类自身还有另一组默认值，示例 YAML 也有 29/8/15 和 46/2/15；不能把任何一组默认值当成所有历史 ID 的真实格式。证据：[GlobalIdDataBaseProperties.java:14](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-sdk/src/main/java/com/longfor/longhub/framework/uniqueid/config/GlobalIdDataBaseProperties.java:14)，[DefaultUniqueIdGenerator.java:58](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/DefaultUniqueIdGenerator.java:58)。

部分算法文件含 Baidu 2017 / Apache-2.0 声明；后续若复用代码需保留适用版权与许可，不将其全部当作无来源的新代码。本轮只做阅读和新方案设计。

## 3. 直接影响数值唯一性的问题

### L-01：Worker 取模并没有保留互斥所有权

[BitsAllocator.java:67](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/BitsAllocator.java:67) 将 workerId 对 `(2^workerBits - 1)` 取模；[DefaultUniqueIdGenerator.java:85](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/DefaultUniqueIdGenerator.java:85) 注释掉超限异常。数据库自增原始 ID 不同，并不保证编码后的 Worker 不同。例如 workerBits=2 时，原始 ID 1 与 4 都编码为 Worker 1；时间和序列相同便得到同一个 long。

可选 ZooKeeper 路径也未弥补：先读取一份子节点列表、比较取模结果，再按**原始 Worker ID**创建临时路径，检查与占用不原子。两个并发启动者可能都通过旧列表，创建不同原始路径却编码到同一个 Worker。列表读取异常还会转为空列表。证据：[AbstractGlobalIdConfiguration.java:101](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-sdk/src/main/java/com/longfor/longhub/framework/uniqueid/config/AbstractGlobalIdConfiguration.java:101)、[AbstractGlobalIdConfiguration.java:140](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-sdk/src/main/java/com/longfor/longhub/framework/uniqueid/config/AbstractGlobalIdConfiguration.java:140)、[AbstractGlobalIdConfiguration.java:152](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-sdk/src/main/java/com/longfor/longhub/framework/uniqueid/config/AbstractGlobalIdConfiguration.java:152)。此处是源码可导出的碰撞条件，不声称当前线上已经碰撞。

新方案要求：禁止取模、编码前完整范围检查；同 Worker 的所有世代由数据库持久上界分割空间，失效会话不能获得新区间。

### L-02：RingBuffer.take 存在未真正领取游标却继续消费的交错

[RingBuffer.java:122](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/buffer/RingBuffer.java:122) 先读取 `currentCursor`，再执行 `updateAndGet(old -> old == tail ? old : old + 1)`，最后用外部旧 `currentCursor` 判断这次是否成功。`updateAndGet` 没有推进时，返回值仍可能大于调用前已过时的快照。

静态反例（序列一致的交错已足够，不依赖弱内存模型）：

1. cursor=0、tail=1，仅槽位 1 待消费。A 读到 currentCursor=0 后暂停。
2. B 读到 0，原子推进 cursor=1，得到槽位 1 的领取权，暂停在读取 flag 之前。
3. A 执行 updateAndGet，此时 old=tail=1，因此没有推进，仍返回 1。
4. A 用 1 与自己的旧快照 0 比较，误认为领取成功；B 也认为领取成功。
5. A、B 都在任一方清零 flag 之前读到 CAN_TAKE，两者均可读取并返回 slots[1]。

flag 检查与清零不是原子领取，不能纠正此前错误。这个交错说明存在重复返回路径；尚未运行确定性 Java 复现或 JCStress。新方案直接 CAS 必须以**本次 CAS 真正成功**为拥有范围的依据；如以后保留 RingBuffer，该反例必须成为确定性并发回归用例。

## 4. 时间和边界问题

### L-03：缓存通过持续消耗未来秒获得吞吐，缺少超前和时间字段上限

[BufferPaddingExecutor.java:74](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/buffer/BufferPaddingExecutor.java:74) 用墙钟秒初始化 lastSecond；[BufferPaddingExecutor.java:133](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/buffer/BufferPaddingExecutor.java:133) 每批调用 `incrementAndGet()`，不等待真实秒、不限制超前；[CachedUniqueIdGenerator.java:68](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/CachedUniqueIdGenerator.java:68) 直接编码，没有经过默认路径的 getCurrentSecond 上限检查。因此进程内缓存时间通常不倒退，但重启、高水位持久化和 Worker 复用的证明仍缺失。

默认 seqBits=15，每个逻辑秒 32768 个 ID。持续 100,000 IDs/s 至少消耗约 3.05 个逻辑秒/真实秒，即未来时间差约每秒增加 2.05 秒（另有预填充/弃号）；编码时间不再近似真实事件时间。新方案使用毫秒布局和明确 0ms 生成超前，提前取得授权与提前生成未来 ID 分开。

基础发号器 [DefaultUniqueIdGenerator.java:131](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/DefaultUniqueIdGenerator.java:131) 仍使用 System.currentTimeMillis；回拨拒绝，序列耗尽后 getNextSecond 忙等，没有等待预算。单改成 nanoTime 不能修复 L-01/L-02，也不能覆盖 JVM 重启。

### L-04：52-bit 常量、64-bit long 位移和范围校验不一致

[BitsAllocator.java:11](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/BitsAllocator.java:11) 将 TOTAL_BITS 设为 52，构造器只检查分配总位数 `>=52`，没有强制总位数为合法正 long 布局；allocate 未完整校验时间/Worker/sequence。

[DefaultUniqueIdGenerator.java:105](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/DefaultUniqueIdGenerator.java:105) 按 TOTAL_BITS 计算移位，但实际运算对象仍是 64-bit long。例如 seqBits=15 时 `(id << 37) >>> 37` 保留低 27 位，不能只取低 15 位序列；Worker 解码同样受影响。新方案绑定单一清单，使用明确掩码/无符号右移并先验证输入范围。

[DefaultUniqueIdGenerator.java:58](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/DefaultUniqueIdGenerator.java:58) 的 epochStr 与 epochSeconds 初始值不一致，SDK 正常调用 setter 会覆盖，但直接使用基础类的默认行为存在歧义。迁移需要真实配置、时区和 ID 样本；新清单固定 UTC Epoch，不借默认字符串推断旧值。

## 5. 资源、稳态和生命周期问题

| 项目 | 静态依据及影响 | 新方案处理 |
|---|---|---|
| 填充异常后可能永久不再填充 | [BufferPaddingExecutor.java:133](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/buffer/BufferPaddingExecutor.java:133) 在 running=true 后没有 finally；provider/put 抛错时标志不复位 | 单一补充状态 owner，异常状态转换和有界重试 |
| 阈值下重复提交任务 | [RingBuffer.java:130](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/buffer/RingBuffer.java:130) 每次低水位消费都提交任务；[BufferPaddingExecutor.java:80](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/buffer/BufferPaddingExecutor.java:80) 用固定线程池，running CAS 在任务执行时才去重 | 入队前合并请求，有界队列、最多一个未完成申请 |
| 假共享优化不等于零分配 | [CachedUniqueIdGenerator.java:68](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/service/CachedUniqueIdGenerator.java:68) 创建 ArrayList<Long> 并装箱；[RingBuffer.java:182](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/buffer/RingBuffer.java:182) 每槽一个 PaddedAtomicLong；padding 字段不能证明所有 JVM 的 cache line 布局 | primitive 批量，按目标 JDK/CPU 测分配率、缓存与 CPU |
| 失败候选未见关闭 | [AbstractGlobalIdConfiguration.java:101](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-sdk/src/main/java/com/longfor/longhub/framework/uniqueid/config/AbstractGlobalIdConfiguration.java:101) 创建并预填充生成器后，碰撞时递归创建下一实例，未关闭前一实例的执行器 | 所有启动中间状态均有明确 rollback/close owner |
| JVM 全局退出钩子只记录首个实例 | [UniqueIdShutdownHook.java:51](/private/tmp/smartscity-id-reference/longhub-framework-unique-id-starter/longhub-framework-unique-id-base/src/main/java/com/longfor/longhub/framework/uniqueid/hook/UniqueIdShutdownHook.java:51) 单例注册 CAS 只接受第一次；示例却有多业务生成器 | 每运行体有独立生命周期，容器协调关闭所有 owner |
| 控制依赖侵入基础模块 | base 引入 Spring/MyBatis/Druid/Curator，SDK 又带 Web、MySQL 驱动等 | Core 无 Spring/JDBC；内置 JDBC 方言，驱动与连接池由使用方选择 |

上述资源风险未做实测量化；不能据此编造泄漏量、重复率或实际 TPS。

## 6. 对新方案的约束

保留本地发号、后台提前准备、减少共享竞争的目标；不照搬取模 Worker、无界未来秒、现有 take 领取协议和全局单例退出状态。新增明确回归目标：Worker 映射碰撞、空队列双消费者交错、填充异常恢复、多个生成器关闭、时间字段溢出、所有支持布局的编解码边界。

审计结论保留；[当前简版方案](README.md)按用户新反馈收敛，[v0.2](archive/v0.2.md)仅为历史提案。下载与关键路径静态审阅已完成；设计批准、实现授权、旧线上配置/ID 样本、数据库 HA 选择、实际性能验收仍是独立事项。
