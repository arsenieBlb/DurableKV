# Completion and verification ledger

This ledger maps the supplied plan to source, executable checks and retained
evidence. Status is updated after actual execution; CI configuration alone is not
an executed platform check. Generated workloads stay under dedicated `target`
test, benchmark and CLI-demo directories; reports are retained in `docs/evidence`.

| Requirement | Implementation and checks | Status |
| --- | --- | --- |
| Independent Java21 library, Maven wrapper, pinned build/test dependencies | pom.xml, mvnw/mvnw.cmd; no storage runtime dependencies; CLI boundary | Implemented; clean verify passed |
| API contract, options, exceptions, model | docs/api.md, Options, checked StoreException hierarchy; EngineTest, ReferenceModel | Passed |
| Written versioned bounded format, checksum and metadata | docs/storage-format.md written before core; Format; FramingTest, including separate header CRC regression | Passed |
| Durable ordering, partial I/O loops and injected failures | DurableKv.mutate; Io/Hooks package-private seams; IoFailureTest | Passed |
| Recovery, strict corruption rejection, tombstones, index, high-water | Format.scan, DurableKv.open/inspect; EngineTest, FramingTest | Passed |
| Thread safety, defensive copies, positional reads | fair ReentrantReadWriteLock; EngineTest; 24 bounded real-time checked ConcurrencyTest histories and close/compaction races | Passed |
| Lifetime process lock and killed-owner release | FileLock, CrashTest second real child process and termination | Passed on Windows/NTFS |
| Failed handles, close/failed-open resource handling | DurableKv state and close; IoFailureTest, EngineTest, FramingTest, ConcurrencyTest | Passed |
| Abrupt child-JVM termination, acknowledgement oracle | CrashProcess/CrashTest; legal issued prefixes include all observed ACKs; unique and overwrite/delete cases | Passed;97 crash/ownership cases |
| At least30 recorded random crash seeds and deterministic boundaries | CrashTest:30 seeded checkpoint histories plus30 unpaused streaming random-kill histories; docs/evidence/crash | Passed;60 seeds recorded |
| Stop-the-world validated generation publication, safe cleanup | prepareGeneration/publish/compact, atomic same-directory manifest move; old log retained until activation | Passed |
| Compaction and initialization interrupted around each publication stage | CrashTest 10 initialization,10 normal compaction,10 all-deleted compaction checkpoints, including1MiB values | Passed |
| Unsupported/ambiguous publication failure and orphan handling | IoFailureTest, FramingTest; no unsafe move fallback; postpublication exception subtype regression | Passed |
| CLI put/get/delete/stats/compact/read-only inspect/explicit recovery | CliTest; executable JAR and docs/evidence/cli-demo.json | Passed |
| Repeatable workloads, percentiles, recovery, growth, pauses, raw measurements | Benchmark, docs/benchmarks.md; both small and larger-than-heap workload; docs/evidence/benchmarks | Executed; raw measurements retained |
| Platform, filesystem, device, JVM, heap, revision, force policy | benchmark metadata; Windows11 NTFS on Samsung990PRO2TB NVMe; BellSoft Java21.0.8; source and JAR hashes | Recorded |
| CI Windows and Linux; clean build and evidence | .github/workflows/verify.yml; local Windows run; Linux not available locally | Windows passed; Linux unverified |
| Final architecture, decisions, limitations, reproduction, two CV bullets | README; docs/api.md, storage-format.md, crash-tests.md, benchmarks.md, this ledger | Delivered |

## Conditional decisions

| Trigger | Decision/evidence | Status |
| --- | --- | --- |
| Atomic manifest replacement supported | ATOMIC_MOVE+REPLACE_EXISTING verified by actual normal compaction, metadata replacement and interrupted publication tests on local NTFS | Applicable; passed |
| Atomic replacement unsupported/fails | Explicit unsupported result keeps old authority; generic uncertainty fails live handle; injected failure tests | Failure branch passed; host supports atomic replacement |
| Directory metadata cannot be synced portably | Document process-crash guarantee only; no full power-loss claim | Applicable; documented |
| Windows open handles interfere with replacement/deletion | Close prepared log/temp manifest before publication; never replace an open generation; close old log before deletion; harness waits for actual OS lock availability after killed-process exit | Windows protocol passed |
| Linux unavailable locally | Add Linux and Windows CI; local Linux outcome remains unverified | Applicable; CI configured, unverified |
| Force is measured as limiting cost | Group commit optional; keep synchronous per-mutation force policy for this release | Optional extension deferred |
| Network demonstration materially improves presentation | Library plus CLI/benchmark demonstrate full engine; no evidence requires a server | Not applicable; HTTP adapter deferred |
| Compaction pauses measured as important | Report actual pause and stop-the-world tradeoff in docs/benchmarks.md; background design deferred | Applicable; measured and documented |
| Unsafe record decode | Fail clearly and preserve complete corrupt files; no automatic salvage | Passed; FramingTest |

## Executed checks and reproduction

On 7 November 2026, BellSoft OpenJDK21.0.8, Windows11 amd64, local NTFS:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd --batch-mode verify
```

The clean build passed **131 tests, zero failures, errors or skips**. The second
run captures the final test-harness protocol-parser refinement as well as all
engine changes. Its complete Maven output is [verify.log](evidence/verify.log).
Structured results and source/test/wrapper hashes are retained in
`docs/evidence/verification.json` and `verification-sources.sha256`.
Per-class Surefire reports are retained in `docs/evidence/tests`;97 crash cases
have98 command/event transcripts in `docs/evidence/crash` (the extra report is
the lock contender). Seed formulas and the legal-history oracle are documented
in [crash-tests.md](crash-tests.md). Raw operations and matching recovered
prefixes allow inspection of acknowledged and unacknowledged mutations.

CLI commands actually executed are retained in `docs/evidence/cli-demo.json`.
The final executable is `target/durablekv-1.0.0-SNAPSHOT.jar`. Benchmark commands,
calculation method, measured results and exact source/JAR hash inventories are
in [benchmarks.md](benchmarks.md) and `docs/evidence/benchmarks`.

The Windows harness originally exposed delayed OS lock cleanup after process-exit
notification, and an I/O fault test exposed an overly broad unsupported-atomic-move
catch after publication. Both were corrected and are covered by passing tests.
Review also prompted a dedicated record-header CRC so inconsistent length-field
damage cannot be silently interpreted as a recoverable suffix.

**Unexecuted mandatory environment check:** Linux is unavailable locally. The
Ubuntu/Windows CI matrix is defined but has not been dispatched or observed;
Linux results and hosted CI results remain unverified. No project was published.
Hardware power-loss testing is outside the demonstrated contract and was not run.

Deferred by scope: replication/Raft, cross-key transactions, SQL, TTL, range scans,
background compaction, group commit, HTTP server, salvage, lock-free/custom memory
work, hosting and unsupported comparative performance claims.
