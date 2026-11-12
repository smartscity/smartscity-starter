# Metrics migration

The unreleased combined `smartscity-observability` / `smartscity-starter-observability` artifacts are replaced by the [three components](../observability/adr/0002-independent-components.md). Remove old coordinates before installing the replacements; never combine old and new JARs or both Boot generations. Existing public Java packages remain unchanged.

Use `com.smartscity:smartscity-starter-metrics` for Boot 2.7/3, or its `-boot4` alternative for Boot 4. Existing property keys under `smartscity.observability` remain unchanged.

No algorithm, log wire format or UDP delivery guarantee changes are implied by this packaging migration.
