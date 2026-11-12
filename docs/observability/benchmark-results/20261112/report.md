# Observability performance evidence

Status: **MEASURED**.

Single shared host, one JMH thread; logging sink excludes filesystem/network I/O. UDP is loopback, not collector throughput or delivery assurance.

Throughput is operations/second; allocation is normalized bytes/operation. JMH confidence intervals and per-fork data remain in jmh.json. Admission percentiles are sampled call costs, not network latency.

| Scenario | Parameters | ops/s | B/op |
|---|---|---:|---:|
| CorrelationBenchmark.clientChild | {} | 4423936.0 | 968.0 |
| CorrelationBenchmark.root | {} | 1729737.2 | 1976.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'HIT'} | 162901.9 | 6232.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'MISS'} | 439363.8 | 0.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'HIT'} | 20453.9 | 49240.3 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'MISS'} | 54367.9 | 0.1 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'HIT'} | 183349.6 | 6232.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'MISS'} | 436769.4 | 0.0 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'HIT'} | 20320.1 | 49240.3 |
| LiteralMatchBenchmark.ac | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'MISS'} | 55293.0 | 0.1 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'HIT'} | 223019.8 | 2104.0 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '1024', 'match': 'MISS'} | 252799.5 | 0.0 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'HIT'} | 29461.6 | 16440.2 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '64', 'inputSize': '8192', 'match': 'MISS'} | 32681.9 | 0.2 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'HIT'} | 15619.0 | 2104.4 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '1024', 'match': 'MISS'} | 15656.3 | 0.4 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'HIT'} | 1970.1 | 16603.5 |
| LiteralMatchBenchmark.sequentialReplace | {'dictionarySize': '1024', 'inputSize': '8192', 'match': 'MISS'} | 1964.5 | 3.5 |
| PipelineBenchmark.logWithMdc | {'inputSize': '1024'} | 75698.0 | 29396.1 |
| PipelineBenchmark.logWithMdc | {'inputSize': '8192'} | 17445.9 | 167401.5 |
| PipelineBenchmark.pipeEncode | {'inputSize': '1024'} | 269434.6 | 13124.0 |
| PipelineBenchmark.pipeEncode | {'inputSize': '8192'} | 65605.6 | 79512.1 |
| PipelineBenchmark.structuredMask | {'inputSize': '1024'} | 144687.5 | 7400.0 |
| PipelineBenchmark.structuredMask | {'inputSize': '8192'} | 19079.2 | 57576.4 |

## Matched literal comparison

Ratio above one favors AC. Both methods assert equal output before each trial; these synthetic dictionary/input distributions do not establish a universal speedup.

| Dictionary | Input | Match | AC / sequential throughput |
|---:|---:|---|---:|
| 64 | 1024 | HIT | 0.73x |
| 64 | 1024 | MISS | 1.74x |
| 64 | 8192 | HIT | 0.69x |
| 64 | 8192 | MISS | 1.66x |
| 1024 | 1024 | HIT | 11.74x |
| 1024 | 1024 | MISS | 27.90x |
| 1024 | 8192 | HIT | 10.31x |
| 1024 | 8192 | MISS | 28.15x |

## UDP trials

Queue capacity 8192 lines; payload limit 1400 bytes; one producer, one sender, one receiver. Each trial warms for two seconds separately. Received counts include the bounded post-close settle period.

| Target/s | Offered/s | Local drops | Delivery gap | Received | Offer p50 / p99 ns | Drain ms | Load |
|---:|---:|---:|---:|---:|---:|---:|---|
| 10000 | 10000 | 0 | 0 | 80000 | 200 / 31458 | 0.19 | ACHIEVED |
| 10000 | 10000 | 0 | 0 | 80000 | 70 / 33020 | 0.19 | ACHIEVED |
| 100000 | 99999 | 0 | 0 | 800000 | 50 / 3405 | 0.18 | ACHIEVED |
| 100000 | 99999 | 0 | 0 | 800000 | 60 / 2444 | 0.13 | ACHIEVED |
| 50000 | 50000 | 0 | 0 | 400000 | 60 / 2463 | 0.24 | ACHIEVED |
| 50000 | 50000 | 0 | 0 | 400000 | 60 / 2394 | 0.14 | ACHIEVED |

UNDERDRIVEN means the producer did not reach 95% of the requested rate. Drops and delivery gaps remain measurements, not hidden failures or proof of reliable UDP. No production capacity or SLA is inferred.
