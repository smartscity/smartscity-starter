# Repository redesign evidence

Date: 2026-10-25. Baseline commit: `f86b741`. Logs in this directory belong to the local restructuring run. [Verification](../../../docs/testing/verification.md) records current results and limits; other evidence directories are historical.

- `reactor.txt`: full Maven reactor build/install with default optional database skips recorded explicitly.
- `compatibility/result.json`: exact independent consumer Boot/JDK matrix, real MySQL allocation, packaged sample startup and private-server cleanup.
- `faults/`: bounded correctness/restart/outage run against owned MySQL/ZooKeeper instances.
- `postgres/`: private PostgreSQL regression on both adapter binaries.
- `preservation.json`: byte-for-byte domain/test/benchmark and historical raw-evidence comparison to baseline.
- `runtime.txt`: framework-free domain probe on installed JDKs.
- `release-dry-run.txt`: source/Javadoc packaging with signing and publication disabled.

No remote workflow or Central publication was executed. JMH discovery and synthetic runner checks do not measure throughput. Exact source fingerprints and final artifact results are recorded after verification completes.
