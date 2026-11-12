# Masking SDK usage

Add `com.smartscity:smartscity-masking:0.1.0-SNAPSHOT`. No Spring starter, logger or metrics library is required.

```java
import com.smartscity.observability.MaskingPolicy;
import java.util.List;

MaskingPolicy policy = new MaskingPolicy(
    true, List.of("account"), List.of("/payment/account"), List.of("private-literal"));
String safe = policy.text().sanitize("account=123; note=private-literal");
// safe: account=***; note=***
boolean redactField = policy.masksField("ACCOUNT");
boolean redactPath = policy.masksPath("/payment/account");
```

Compile once and share across threads. Collections are copied, no close is needed, and policies are not hot-reloaded. `TextSanitizer.defaults()` enables the built-in credential recognizer; `LiteralMasker` supports a literal-only dictionary. Unknown secrets require explicit rules. Text over 65,536 characters is omitted, including when masking is disabled. Field/path decisions apply only to configured fields/paths; text recognition additionally includes built-in credential names.

JSON/form body parsing is provided by the [logging adapter](../logging/USER_GUIDE.md), not the zero-dependency SDK. For Spring log configuration, use the [single integration example and property reference](../observability/USER_GUIDE.md).
