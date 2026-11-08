# Benchmark method

The runner is `io.durablekv.Benchmark` in the executable library JAR. It uses one
thread, synchronous `force(true)` for every mutation and a seeded workload. These
are local end-to-end workload measurements, not a claim of superiority over other
databases or a statistically rigorous cross-machine microbenchmark.

From a verified build, create a fresh run directory for each invocation:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -Xms32m -Xmx32m -cp target/durablekv-1.0.0-SNAPSHOT.jar io.durablekv.Benchmark target/benchmark-data/small target/benchmark-results/small 1000 1024 424242
& "$env:JAVA_HOME\bin\java.exe" -Xms32m -Xmx32m -cp target/durablekv-1.0.0-SNAPSHOT.jar io.durablekv.Benchmark target/benchmark-data/large target/benchmark-results/large 640 65536 424242
```

On Linux use `"$JAVA_HOME/bin/java"`. Data directories must be empty; the runner
refuses to overwrite a previous run. Output directories contain
`environment.properties`, `latencies.csv`, `summary.csv` and `result.txt`.

Warm-up uses a separate store with 100 forced writes and 1,000 reads. Measured
stages are N unique writes, 2N seeded random reads, N mixed operations (60% reads,
30% puts, 10% deletes), N overwrites targeting 10% of keys, one compaction and one
reopen including close. Keys are `k` followed by a decimal integer; environment
metadata records their size, value size, dataset count, seed, heap, JVM, OS,
filesystem, thread count, force policy and source revision description. Platform
capture and source SHA256 inventory accompany retained results.

Raw CSV records each operation latency from `System.nanoTime()`. The calculation
code lives in `Benchmark.measure` and `percentile`: sort samples, choose nearest
rank `ceil(p * N) - 1`, and divide operation count by measured workload wall time
for throughput. Wall time includes recording raw measurements; individual
latencies include the invoked operation and read-value validation. Single-sample
compaction and reopen percentiles are simply their measured duration. This small
runner exposes overhead and pauses; it does not hide them behind buffered writes.

`log_bytes_before` and `log_bytes_after` report the active generation size around
each stage, including framing. The large case starts with 40 MiB of value payload
under a 32 MiB maximum heap, demonstrating disk-resident values. It does not imply
an unbounded index: heap consumption grows with live keys, and the workload holds
only one shared 64 KiB input value plus per-operation samples.

Only synchronous durability is supported, so no buffered-write comparison is
presented. Group commit is optional and deferred. Compaction pauses are measured
and documented; background compaction requires a separate concurrency protocol.

## Measured outcomes

Executed on 7 November 2026 with BellSoft OpenJDK 21.0.8, Windows 11 amd64, NTFS
on the C: volume of a Samsung SSD 990 PRO 2TB NVMe, `-Xms32m -Xmx32m`, one thread,
seed 424242. The source was an uncommitted independent project in an existing
parent checkout; a source SHA256 inventory and the measured JAR's SHA256 identify
the tested revision under [evidence/benchmarks](evidence/benchmarks).

| Workload | 1,000 keys / 1 KiB values | 640 keys / 64 KiB values |
| --- | ---: | ---: |
| Forced unique writes, ops/s | 566.4 | 688.4 |
| Write latency p50 / p95 / p99, ms | 1.717 / 2.779 / 3.300 | 1.516 / 1.763 / 2.543 |
| Reads, ops/s | 196,775 | 29,270 |
| Read latency p50 / p95 / p99, ms | 0.0036 / 0.0078 / 0.0142 | 0.0253 / 0.0541 / 0.2010 |
| Mixed operations, ops/s | 1,559.2 | 1,666.4 |
| Overwrites, ops/s | 620.6 | 617.3 |
| Compaction pause, ms | 49.90 | 101.22 |
| Reopen including close, ms | 6.78 | 22.35 |
| Active log before → after compaction, bytes | 2,440,390 → 976,206 | 96,523,479 → 38,818,573 |

Both runs completed. The large run initially persisted 41,943,040 value bytes,
greater than its 33,554,432-byte maximum heap. Raw per-call measurements, summaries,
environment properties, source hashes and device capture are retained in the
evidence directory. These are one run per workload configuration; variation across
runs has not been quantified. The approximately 1.5–1.7 ms forced-write median is
consistent with force/storage overhead being an important limit, but the runner
does not isolate the cause. Group commit remains deferred. The measured 101 ms
large-case compaction pause is a documented limitation for latency-sensitive
callers; no background-compaction behavior is claimed.
