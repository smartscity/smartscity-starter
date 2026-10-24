# 项目范围

`smartscity-starter` 是 `com.smartscity` 下的 Java / Spring Boot 基础组件工程。当前只实现分布式唯一 ID。

本轮已授权 API、秒级发号核心、RingBuffer、MySQL/TiDB/PostgreSQL 适配、JavaScript 档 ZK 占用、Starter、指标/健康与正确性验收。具体边界以[已确认方案](proposals/id-v1/README.md)为准。

Core 运行目标仍为 JDK 9～27，Starter 目标为 Boot 2.7、3.3～3.5、4.0～4.1；按[兼容矩阵](03-Compatibility-Matrix.md)实际验证后逐项声明，不承诺全部笛卡尔积。

不创建其他组件、HTTP 发号服务或 ORM 集成。性能测试明确暂停；提交、推送、发布未授权。当前停点见[阶段计划](phases/current.md)。
