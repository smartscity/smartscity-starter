# Masking migration

The unreleased combined `smartscity-observability` / `smartscity-starter-observability` artifacts are replaced by the [three components](../observability/adr/0002-independent-components.md). Remove old coordinates before installing the replacements; never combine old and new JARs or both Boot generations. Existing public Java packages remain unchanged.

Use `com.smartscity:smartscity-masking` directly; no Spring configuration is required. LiteralMasker, RedactionRanges and TextSanitizer retain their packages. MaskingPolicy exposes immutable field/path decisions. JSON/form parsing remains in the logging integration.

No algorithm, log wire format or UDP delivery guarantee changes are implied by this packaging migration.
