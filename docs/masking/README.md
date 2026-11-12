# Masking SDK

`com.smartscity:smartscity-masking` is a pure Java SDK with zero production dependencies. [Usage](USER_GUIDE.md) and [decisions](adr/README.md).

| Owner | Responsibility | Lifetime / invariant |
|---|---|---|
| LiteralMasker | Immutable Aho-Corasick dictionary | Compiled once, bounded input and dictionary |
| RedactionRanges | Original-source overlap union | One invocation; lazy bounded scratch space |
| TextSanitizer | Literal and credential recognition | Shared immutable policy; no logging dependency |
| MaskingPolicy | Copied field names, exact JSON Pointers and text sanitizer | Validated immutable object; no framework or resource lifecycle |

Business callers use decoded strings and field/path decisions with their own data model. The SDK does not serialize JSON or mutate objects. The logging adapter owns Jackson parsing, payload completeness, depth and encoding. Legacy Java packages are preserved, including `com.smartscity.observability.logging.TextSanitizer`; that package name does not imply a logging dependency.

See [migration](migration.md), [compatibility](compatibility.md) and [verification](testing.md).
