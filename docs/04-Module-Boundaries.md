# 模块边界与管理

API/SPI/Core/AutoConfigure/Starter 已进入实现；testkit 与 benchmark 仍为预留模块。依赖由 POM 显式表达。

| 模块 | 当前直接依赖 | 职责与边界 |
|---|---|---|
| starter-bom | 无；dependencyManagement 列出五个生产模块 | 只管理 `com.smartscity` 的 ID 生产制品 |
| id-api | 无 | 面向用户的最小 API；零第三方依赖 |
| id-spi | id-api | 扩展边界；不实现节点分配 |
| id-core | id-api、id-spi | 算法、状态、不变量；零 Spring/日志/观测框架依赖 |
| id-spring-boot-autoconfigure | id-core | 框架适配与组装；含 optional Boot/ZK/Micrometer 依赖，JDBC/ZK 适配留在本模块 |
| id-spring-boot-starter | id-api、id-spi、id-core、autoconfigure | 只聚合依赖，不放业务代码 |
| id-testkit | id-api、id-spi | 测试设施占位，不进入生产依赖 |
| id-compatibility-tests | test-scope core、starter、testkit | 真实运行探针与独立 Boot BOM 验证 |
| id-benchmark | id-core、JMH | 独立性能实验，默认不构建/运行 |

表中省略共同前缀 `smartscity-`。不设置包含全部组件的 God Starter，不创建 `common` / `utils` 模块。

## Maven 管理

- 根坐标 `com.smartscity:smartscity-starter:0.1.0-SNAPSHOT`，packaging 为 `pom`，不继承 Boot Parent。
- 子模块继承根版本；内部依赖显式引用 `${project.version}`。根父 POM 不导入自己的子 BOM，避免父子模型循环。
- BOM 管理 API、SPI、Core、AutoConfigure、Starter 五个生产制品；不管理外部生态或测试/性能模块。
- 默认 reactor 包含根工程、BOM 与五个生产模块，共七个项目。
- `verification` profile 追加 testkit 与 compatibility-tests；`benchmarks` profile 追加 benchmark，均无自动激活。
- samples 含独立可运行示例，不加入 reactor。
- 统一固定构建插件版本，配置 Java 9 release。生产 JAR 声明 Automatic-Module-Name；不创建 `module-info.java`。
- 第三方生产依赖限于适配/Starter；JUnit、数据库测试驱动等为 test scope。JMH 仅在独立 benchmark 模块中。
- 当前 deploy 跳过；发布条件由[发布政策](09-Release-Policy.md)拥有。

新增模块时，先更新本表、根 reactor/profile 和模块 README；只有生产制品才按需要加入 BOM。验证依赖方向及实际构建，不将空目录视为已交付组件功能。
