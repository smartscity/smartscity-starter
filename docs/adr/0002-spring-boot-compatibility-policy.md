# ADR 0002 — Spring Boot 兼容政策

状态：`ACCEPTED_FOR_SCAFFOLD`，2026-10-24。

不继承 Spring Boot Parent，BOM 不管理 Spring 生态版本。Core 与框架隔离，AutoConfigure 与 Starter 分开；先保留一个 AutoConfigure 模块，是否需要代际拆分由真实兼容结果决定。

当前不锁定或声明 Boot 依赖。候选版本线见[兼容矩阵](../03-Compatibility-Matrix.md)，精确补丁版本、官方 JDK 范围与编译基线在实现阶段核实。

没有实际运行证据的组合不能进入正式支持列表。此决策只确定工程隔离与证据政策，不宣称已跨 Boot 2、3、4 兼容。
