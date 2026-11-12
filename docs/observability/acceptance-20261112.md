# Observability requirement acceptance

This review evaluates the scope approved in the conversation: one starter family, four independent capability switches, parent/current correlation aliases, safe pipe logging, masking and Prometheus plus active StatsD/UDP. It does not extend that scope to unimplemented framework adapters or production deployment.

| Approved requirement | Implementation and evidence | Assessment |
|---|---|---|
| One starter with four independent switches | One family with generation alternatives; all 16 switch combinations exercised | Met within the supported Boot generations |
| traceid, caller/called, spanid/cspanid | W3C propagation; spanid is parent and cspanid is current; native-provider ownership and executor/nested-scope regressions | Met for Servlet/RestTemplate and recorded providers |
| Fast sensitive-value masking | Immutable AC dictionary, original-source union of overlapping matches, field/path masking and bounded input | Functionally met; speed depends on dictionary size and hit pattern |
| Entry/completion logs with event types | Approved event tags, caller source, timing/outcome, explicit Logback encoder and optional bounded body capture | Met for supported HTTP integrations |
| Pipe format without v=1; embedded pipe escaping | Double-pipe field format with reversible percent escaping, one physical line and record limits | Met; compressed storage savings were not measured |
| Prometheus exporter | Opt-in Micrometer registry/endpoint and real scrape verification | Met |
| Active UDP metrics | Micrometer StatsD line production, bounded blocking queue, one UDP socket-writing consumer, statsd_exporter | Met; local acceptance does not imply network delivery |
| Thread safety and cleanup | Concurrent producers/closers, sub-millisecond/interrupted shutdown, capture completion and real application shutdown tests | Passed in the executed test scope |
| Functional compatibility | Boot 2.7.18/3.5.16/4.0.9 on JDK 17/21; real Linux statsd_exporter | Recorded by exact-revision Actions evidence |
| Performance evidence | 24 JMH cases and six loopback UDP trials; raw forks, allocation, drops and cleanup retained | Measured, with explicit workload and host limits |

## Interpretation

The confirmed functional scope is implemented and has executed verification. The selected AC implementation is not universally faster: the first measurement favored it for the 1024-word dictionary, while 64-word HIT workloads were slower than sequential replacement and allocated more. Log encoding also has material allocation costs. The supplied requirements did not define a production TPS/P99, allocation ceiling or storage-reduction target, so these experiments cannot establish such acceptance thresholds.

The UDP experiment uses one producer and a loopback receiver. Functional tests separately contend multiple producers/closers and verify real statsd_exporter semantics. Short measurements do not establish multi-producer capacity, collector capacity, guaranteed UDP delivery or long-duration stability. Micrometer retains its own meter-polling scheduler; the single-thread statement applies to UDP socket writing.

Boot 2.7 native-provider combinations, WebFlux/WebClient, Feign, database/messaging tracing and native images remain outside the verified support scope. These boundaries are listed in [compatibility](compatibility.md). See [ID CI corrections](../id/ci-review-20261112.md) and the [benchmark record](benchmark-results/20261112/README.md) for detailed evidence and retained failures.

## Full CI follow-up

The ID database/restart correction passed at revision `c6d0178f894159d7b070159f684aaeaafddd9801` on the dedicated validation branch. Both triggered workflows completed successfully on their first attempt: **23/23 jobs**. The real database/restart gate executed **43 cases without skips**; reactor totals are **247 passes and four explicit conditional skips**, ID compatibility totals **112 passes**, and the dedicated observability matrix **81 passes with two intentional skips**. Counts overlap across jobs.

See the [full CI evidence](../id/verification-ci-20261112.json) and [latest performance recheck](benchmark-results/20261112-ci-followup/README.md). The approved functional scope and this CI acceptance are met. Performance is measured with known tradeoffs; unspecified production latency/capacity/allocation or storage targets cannot be declared met. No release, deployment or merge was performed.
