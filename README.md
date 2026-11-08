# durablekv

[![Checks](https://github.com/arsenieBlb/DurableKV/actions/workflows/verify.yml/badge.svg)](https://github.com/arsenieBlb/DurableKV/actions/workflows/verify.yml)

[Verification and limitations](docs/application-review.md)

A Java 21 library and CLI implementing an append-only persistent key-value store
using the Java standard library. Each successful mutation is forced to storage
before it becomes visible. Recovery rebuilds an in-memory index from a versioned,
bounded CRC32C log; a manifest selects the authoritative generation.

## Build and run

Install a JDK 21 or newer and set `JAVA_HOME` to it. Maven is supplied by the pinned
wrapper (first use downloads Maven 3.9.11). There are no runtime dependencies.

```powershell
.\mvnw.cmd clean verify
& "$env:JAVA_HOME\bin\java.exe" -jar target/durablekv-1.0.0-SNAPSHOT.jar put target/cli-demo greeting hello
& "$env:JAVA_HOME\bin\java.exe" -jar target/durablekv-1.0.0-SNAPSHOT.jar get target/cli-demo greeting --utf8
& "$env:JAVA_HOME\bin\java.exe" -jar target/durablekv-1.0.0-SNAPSHOT.jar inspect target/cli-demo
& "$env:JAVA_HOME\bin\java.exe" -jar target/durablekv-1.0.0-SNAPSHOT.jar compact target/cli-demo
```

On Linux use `sh ./mvnw clean verify` and `$JAVA_HOME/bin/java` or `java` from that
JDK. Run `java -jar target/durablekv-1.0.0-SNAPSHOT.jar --help` for all commands.
`get` returns Base64 unless `--utf8` is specified; binary input uses
`put DIRECTORY KEY --base64 VALUE`. Missing keys exit 1, errors exit 2, and
`inspect` reports a recognized incomplete suffix with exit 3. Inspection never
trims a suffix; explicit `recover DIRECTORY` opens an existing store and trims
only that recognized incomplete tail. Normal data commands also recover on open.

## Library

```java
import io.durablekv.DurableKv;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

try (var store = DurableKv.open(Path.of("target", "example-data"))) {
    long sequence = store.put("hello", "world".getBytes(StandardCharsets.UTF_8));
    byte[] value = store.get("hello").orElseThrow();
    store.delete("hello");
    store.compact();
    System.out.println(store.stats());
}
```

Keys are nonempty strict UTF-8 strings up to 1 KiB, with no normalization. Values
are byte arrays up to 1 MiB, including empty and binary values. `Options` can lower
limits. Every put and delete, including deletion of an absent key, receives a
monotonic sequence. Values returned by `get` are defensive copies. Close is
idempotent; operations on a closed or failed handle throw `IllegalStateException`.
Storage exceptions are checked `IOException` subclasses, while invalid arguments
raise `IllegalArgumentException`.

## Design and guarantees

A read/write lock serializes mutations, compaction and close while permitting
concurrent positional reads. A process-held OS file lock excludes another store
owner. The index stores locations, sizes and versions of live values; value bytes
remain on disk. The index therefore consumes memory proportional to live keys.

Mutation ordering is append, `FileChannel.force(true)`, index publication, return.
An ambiguous append, force or generation activation failure fails the live handle;
it must be closed and reopened. A mutation returning an I/O error can still appear
after recovery. Complete corruption is an error; only a physically incomplete
final mutation record can be trimmed. A nonempty directory with missing metadata
is rejected, including interrupted initialization.

Compaction freezes operations, prepares and validates a new generation preserving
live sequences and the sequence high-water mark, forces it and a temporary
manifest, then publishes with atomic same-filesystem replacement. Unsupported
atomic replacement has no unsafe fallback. Recovery follows the committed
manifest and never guesses from filenames. Compaction needs temporary space for
both generations and pauses all operations.

The demonstrated durability scope is abrupt process termination on the tested
local filesystem. Hardware power-loss persistence additionally depends on OS,
filesystem, device and directory metadata behavior. Java does not supply a
portable directory sync guarantee. See [storage format](docs/storage-format.md),
[completion and verification evidence](docs/completion.md), and
[benchmark method and measured outcomes](docs/benchmarks.md).

## Verification and scope

`clean verify` runs data and corruption cases, injected I/O faults, bounded
linearizability histories, real-process locking and abrupt process-kill recovery,
including deterministic interruption checkpoints and at least 30 recorded seeds.
Generated data and test reports remain under `target/`; recorded acceptance
evidence is retained under `docs/evidence/`. CI defines Java 21 checks on Windows
and Linux; local execution evidence is recorded separately from unexecuted CI.

The first release has one process with multiple application threads, one active
log, synchronous durable mutations and explicit compaction. Replication, consensus,
multi-key transactions, SQL, TTL, range scans, background compaction, custom
allocators, network service, salvage and production hosting are deferred.

## Build references

Build choices were checked against the official [Maven Wrapper documentation](https://maven.apache.org/tools/wrapper/),
[JUnit 5.13.4 guide](https://docs.junit.org/5.13.4/user-guide/),
[compiler release option](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html),
and [Surefire JUnit Platform documentation](https://maven.apache.org/surefire/maven-surefire-plugin/examples/junit-platform.html).
The storage API references are linked in the storage format specification.

## Proposed CV bullets

- Implemented an independent Java 21 persistent key-value engine with CRC32C
  record validation, synchronous durable mutations, indexed reads, process locking
  and atomic-manifest compaction.
- Measured a 40 MiB value dataset under a 32 MiB heap on Windows/NTFS, recording
  approximately 688 forced writes/s and a 101 ms compaction pause with reproducible
  raw latency and environment evidence.
