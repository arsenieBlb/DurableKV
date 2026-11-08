# Abrupt process recovery tests

`CrashTest` launches `CrashProcess` in a separate JVM using the same Java runtime
as the test runner. The child has no shutdown hook and never closes the store on
EOF or in a `finally` block. The parent uses `Process.destroyForcibly()` and waits
for termination before opening the files itself. Test stores stay under
`target/test-data/crash`; raw command/event transcripts stay under
`target/crash-evidence`. Saved handoff transcripts are in `docs/evidence/crash`.

Run on Windows with Java 21 selected:

```powershell
.\mvnw.cmd '-Dtest=CrashTest' test
```

On Linux the corresponding command is `./mvnw -Dtest=CrashTest test`.

## Cases and oracle

The suite contains 97 executable cases:

| Cases | What they exercise |
| --- | --- |
| 6 | Killing at partial append, complete append, force completion, index publication, before response acknowledgement, and after acknowledgement; unique keys make acknowledged values unambiguous. |
| 30 | Seeded overwrite/delete histories, killed at a deterministic storage/response checkpoint after a seeded number of operations. Seeds are `0xD00AB1E000 + 0..29`. |
| 30 | An unpaused child processes a stream of seeded random puts/deletes while the parent kills it after a seeded issued-operation event. Seeds are `0xC2A50000 + 0..29`. No checkpoint stops the child's mutations in these cases. |
| 10 | Initial creation interrupted after each generation preparation and manifest publication boundary. Pre-publication remnants must fail as missing authoritative metadata, preserving files; post-publication stores reopen empty. |
| 10 | Compaction interrupted after the same preparation/publication boundaries, with overwritten and deleted keys, an empty value and a 1 MiB value. The manifest must select the expected old or new generation. |
| 10 | Compaction of an all-deleted store interrupted at every boundary, retaining the sequence high-water mark and never resurrecting tombstoned keys. |
| 1 | A second real JVM is rejected while an owner holds the store; killing the owner releases the lock, preserves its acknowledged value, and permits a new JVM to open it. |

The parent supplies operations and records `ISSUED` and `ACK` messages. A child
prints an acknowledgement only after the mutation method returns. In unpaused
cases the parent drains the output pipe **after termination** so acknowledgements
already emitted are never lost from the oracle. The recovered sequence and map
must match an actual issued prefix whose length includes every acknowledgement.
The oracle allows a complete issued-but-unacknowledged overwrite or delete to
survive; it does not incorrectly compare against only the last acknowledged map.
Each transcript records its seed, parent commands, child events, acknowledgement
count, issued count, recovered sequence and matching legal prefix. Seeded source
operations remain reproducible even though operating-system scheduling can change
the exact observed interruption point.

The ten preparation/publication boundaries are generation creation, generation
write completion, generation validation, generation force, manifest write,
manifest force, immediately before publication, completed publication, generation
activation and old-generation cleanup. Package-private I/O/checkpoint seams are
used only by tests; normal public opens install no test hooks.

## Windows termination details and scope

On the tested Windows system, a process-exit notification sometimes preceded
final operating-system lock cleanup. After confirming process death, the harness
waits with a bounded deadline for the actual `FileLock` availability condition.
It never asks the child to close the store, deletes a lock file, or retries an
engine operation to hide a recovery failure. The streamed parent writer can
receive an expected broken-pipe error after the child is killed; only that
parent-side cleanup error is discarded after confirmed termination.

These tests demonstrate abrupt **process** termination recovery on the recorded
local filesystem. They do not cut electrical power, flush device caches by
external means, or establish hardware power-loss durability. Running the same
suite on a different platform is still required to verify that platform; a CI
definition alone is not a passing result.
