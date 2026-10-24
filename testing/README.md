# 测试入口

先读[测试清单](TEST-INVENTORY.md)、[门禁结果](GATE-RESULTS.md)和[测试政策](../docs/07-Testing-and-Gates.md)。

`smartscity-id-compatibility-tests` 由 `verification` profile 管理，也可独立指定 Boot BOM 运行。`smartscity-id-testkit` 和 `smartscity-id-benchmark` 暂为占位；性能测试不执行。

初次实现记录放在 `evidence/id-implementation/`，修复后复审记录放在 `evidence/id-review/`；早期结果单独保留，不作为当前制品证据。真实数据库用例通过显式测试 URL 启用，普通构建未提供 URL 时会跳过。停机用例另需显式开关，且仅允许指定的隔离测试端口。
