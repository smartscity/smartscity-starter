# ADR 0001 — 构建与字节码目标分离

状态：`ACCEPTED_FOR_SCAFFOLD`，2026-10-24。

按输入方案，父 POM 要求 JDK 21 构建，并设置 `maven.compiler.release=9`。固定 Maven Wrapper 与构建插件版本，不要求整个工具链在 Java 9 上运行。

当前无 Java 源码，无法提供字节码或低版本运行证据。未来需要核实依赖自身的字节码要求，并用目标 JDK 执行实际产物；release 选项不能证明第三方依赖兼容。

Java 9～27 运行范围仍是[待验证目标](../03-Compatibility-Matrix.md)。未来 Boot 测试工程独立选择运行 JDK，不通过修改生产编译目标迁就测试工具。
