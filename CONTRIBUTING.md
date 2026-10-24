# 贡献指南

先读 [AGENTS.md](AGENTS.md)、[文档索引](docs/00-README.md)、[当前阶段](docs/phases/current.md)及受影响模块 README。

当前范围为已确认方案内的分布式 ID 组件；性能测试按用户要求暂不执行。

设计事实写入其 owning 文档；公共契约冻结或修改记录 ADR。不要把草案当成已批准设计，不要新增无需求的组件或通用工具模块。

使用 JDK 21 和仓库 Maven Wrapper。提交说明推荐 English Conventional Commits，说明具体变化及实际检查；未经运行的测试写 `NOT_RUN`。保留无关工作区内容，提交、push、tag 和发布遵循实际授权。

功能实现阶段按[测试政策](docs/07-Testing-and-Gates.md)补齐验收。骨架 `verify` 的成功不能作为功能测试结果。
