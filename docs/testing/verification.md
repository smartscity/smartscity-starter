# Verification status

Date: 2026-10-25. The user accepted the starter-family structure and then stopped test execution. The current round groups the sample applications, consolidates documentation, adds the IDE style scheme and prepares a local commit of all current project changes.

Legend: **✅ PASS** means the named check succeeded for its stated snapshot; **❌ FAIL** records a failed attempt; **⏸ NOT_RUN** means the check was not executed. Historical success is not carried forward to changed source.

## Current round

| Gate | Status | Actual scope |
|---|---|---|
| Sample reactor paths, parent references, XML and documentation links | ✅ PASS | Static file inspection only; [record](../../test/evidence/starter-final-tidy-20261025/static.txt) |
| Google Java formatting | ✅ PASS | [Formatter-only run](../../test/evidence/starter-final-tidy-20261025/format-only.txt); no compilation or test goals invoked |
| Unit/integration tests | ⏸ NOT_RUN | The user stopped execution; no Surefire/Failsafe test phase was reached by this chat's attempted builds |
| Boot/JDK compatibility and packaged sample execution | ⏸ NOT_RUN | Not executed for the current sample layout/source snapshot |
| Real database/ZooKeeper and failure/restart scenarios | ⏸ NOT_RUN | No test services started by this round |
| Compilation, generated metadata and packaged artifact checks | ⏸ NOT_RUN | Not repeated after the final sample and documentation changes |
| Benchmarks | ⏸ NOT_RUN | Explicitly excluded; existing comparison files are retained without rerunning them |

## Interrupted validation attempts

| Attempt | Status | Outcome |
|---|---|---|
| Initial reactor build | ❌ FAIL | Spotless rejected a malformed Javadoc opener and a long comment before tests; [log](../../test/evidence/starter-family-validation-20261025/reactor-initial.txt) |
| Reactor after correcting the opener | ❌ FAIL | Spotless still rejected the long comment; [log](../../test/evidence/starter-family-validation-20261025/reactor-after-comment-fix.txt) |
| Formatter application | ✅ PASS | Formatting completed at that snapshot; [log](../../test/evidence/starter-family-validation-20261025/format.txt). Subsequent edits were not built or tested. |

## Earlier review snapshot

The earlier [source fingerprints](../../test/evidence/starter-family-20261025-review/sources.sha256) identify the scope of the following results. They predate subsequent SDK changes and the sample-family move.

| Gate | Status | Evidence and limitation |
|---|---|---|
| Main-source compilation | ✅ PASS with warnings | [11-project compile](../../test/evidence/starter-family-20261025-review/main-compile.txt); JDK 21, Maven 3.9.15, tests disabled; optional Jackson annotation warnings |
| Google formatting and static repository inspection | ✅ PASS | [Formatter](../../test/evidence/starter-family-20261025-review/format.txt), [repository](../../test/evidence/starter-family-20261025-review/repository.txt) |
| Source archives and Javadoc/doclint | ✅ PASS with warnings | [Generation](../../test/evidence/starter-family-20261025-review/api-docs.txt); author jack rendered; optional Jackson annotation and Java 9 API-link warnings |
| Configuration metadata/reference inspection | ✅ PASS | [Generated files](../../test/evidence/starter-family-20261025-review/generated-files.txt); 31 property descriptions and matching reference names |

The [ID test inventory](../id/testing.md) describes future verification commands. The [historical registry report](../id/registry-verification-history.md) and separate optimization reports retain their recorded snapshots; they do not certify this combined working state. TiDB, unlisted JDKs, native images and JPMS remain ⏸ NOT_RUN.
