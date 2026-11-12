# Observability performance evidence

Status: **MEASURED**.

Single shared host, one JMH thread; logging sink excludes filesystem/network I/O. UDP is loopback, not collector throughput or delivery assurance.

Throughput is operations/second; allocation is normalized bytes/operation. JMH confidence intervals and per-fork data remain in jmh.json. Admission percentiles are sampled call costs, not network latency.

| Scenario | Parameters | ops/s | B/op |
|---|---|---:|---:|
| CorrelationBenchmark.clientChild | {} | 2699293.0 | 968.0 |
| CorrelationBenchmark.root | {} | 1076742.6 | 1976.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'HIT'} | 84782.6 | 6232.1 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'MISS'} | 220866.8 | 0.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'HIT'} | 10419.9 | 49240.7 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'MISS'} | 24950.9 | 0.3 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'HIT'} | 85226.5 | 6232.1 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'MISS'} | 213162.1 | 0.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'HIT'} | 10433.8 | 49240.7 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'MISS'} | 25045.4 | 0.3 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'HIT'} | 127307.6 | 2104.1 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'MISS'} | 141810.6 | 0.0 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'HIT'} | 17512.5 | 16440.4 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'MISS'} | 19261.7 | 0.4 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'HIT'} | 8602.5 | 2104.8 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'MISS'} | 8810.2 | 0.8 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'HIT'} | 1178.7 | 16605.9 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'MISS'} | 1204.0 | 5.7 |
| PipelineBenchmark.logWithMdc | {'inputSize': '1024'} | 45645.1 | 29409.2 |
| PipelineBenchmark.logWithMdc | {'inputSize': '8192'} | 9318.5 | 167401.3 |
| PipelineBenchmark.pipeEncode | {'inputSize': '1024'} | 234114.7 | 13136.0 |
| PipelineBenchmark.pipeEncode | {'inputSize': '8192'} | 38549.1 | 79512.2 |
| PipelineBenchmark.structuredMask | {'inputSize': '1024'} | 77798.0 | 7400.1 |
| PipelineBenchmark.structuredMask | {'inputSize': '8192'} | 9833.3 | 57576.7 |

## Matched literal comparison

Ratio above one favors AC. Both methods assert equal output before each trial; these synthetic dictionary/input distributions do not establish a universal speedup.

| Dictionary | Input | Match | AC / sequential throughput |
|---:|---:|---|---:|
| 64 | 1024 | HIT | 0.67x |
| 64 | 1024 | MISS | 1.56x |
| 64 | 8192 | HIT | 0.59x |
| 64 | 8192 | MISS | 1.30x |
| 1024 | 1024 | HIT | 9.91x |
| 1024 | 1024 | MISS | 24.19x |
| 1024 | 8192 | HIT | 8.85x |
| 1024 | 8192 | MISS | 20.80x |

## UDP trials

Queue capacity 8192 lines; payload limit 1400 bytes; one producer, one sender, one receiver. Each trial warms for two seconds separately. Received counts include the bounded post-close settle period.

| Target/s | Offered/s | Local drops | Delivery gap | Received | Offer p50 / p99 ns | Drain ms | Load |
|---:|---:|---:|---:|---:|---:|---:|---|
| 10000 | 10000 | 0 | 0 | 80000 | 141 / 23154 | 0.24 | ACHIEVED |
| 10000 | 10000 | 0 | 0 | 80000 | 90 / 27893 | 0.18 | ACHIEVED |
| 100000 | 99999 | 0 | 0 | 800000 | 61 / 7324 | 0.16 | ACHIEVED |
| 100000 | 99999 | 0 | 0 | 800000 | 80 / 4519 | 0.15 | ACHIEVED |
| 50000 | 50000 | 0 | 0 | 400000 | 70 / 4479 | 0.18 | ACHIEVED |
| 50000 | 50000 | 0 | 0 | 400000 | 70 / 4448 | 0.13 | ACHIEVED |

UNDERDRIVEN means the producer did not reach 95% of the requested rate. Drops and delivery gaps remain measurements, not hidden failures or proof of reliable UDP. No production capacity or SLA is inferred.
