# Metrics compatibility

Boot 2.7/3 use the base starter artifact (Java 9 bytecode); Boot 4 uses the alternative `-boot4` artifact (Java 17 bytecode). Never install both generation artifacts.

The [framework matrix](../observability/compatibility.md) records exact framework/JDK combinations. The [current split verification](../observability/component-split-verification.md) distinguishes newly executed results from historical evidence.
