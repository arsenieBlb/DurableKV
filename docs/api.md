# Library contract

Java 21+, package `io.durablekv`, no runtime dependencies beyond the JDK.

```java
try (var store = DurableKv.open(Path.of("my-store"), Options.defaults())) {
    long sequence = store.put("café", new byte[] {0, 1, -1});
    Optional<byte[]> value = store.get("café");
    long deletionSequence = store.delete("café");
    store.compact();
    Stats counters = store.stats();
}
```

`open(Path)` uses default limits. New empty directories are initialized; existing
stores recover their selected generation. Existing nonempty directories without
authoritative metadata fail. Normal open may truncate only a recognized incomplete
mutation tail, forcing that repair before returning. Open acquires an exclusive
process-held file lock and keeps it until close. Failed open releases resources.

`put` copies input, appends a full PUT record, calls `force(true)`, updates the index,
and returns the next positive sequence. `delete` does the same for a tombstone,
even when the key is missing. Sequences increase once per mutation, never during
compaction; exhaustion at Long.MAX_VALUE rejects further mutations. `get` returns
an Optional containing a newly allocated byte array, including for empty values.
Missing keys return Optional.empty(). Keys are strictly encoded UTF-8 strings;
malformed surrogate sequences are rejected. No Unicode normalization occurs.

Default limits are 1 KiB per encoded key and 1 MiB per value. `Options` can lower
write limits (value limit may be zero). Recovery and reads use the format maxima,
so opening with stricter write limits still permits reading existing values.
Keys/values are validated before record allocation or writes. The caller must not
modify an input array concurrently with a call copying it.

`compact` excludes all operations until the new validated, forced generation and
manifest are published and activated. `stats` returns a coherent snapshot:

| Counter | Meaning |
| --- | --- |
| generation | Authoritative generation UUID |
| sequence | Last recovered or completed mutation sequence, including deleted keys |
| liveKeys | Current index entry count |
| logBytes | Active generation bytes, including header and obsolete records |
| records | Active generation snapshot plus mutation record count, including tombstones |
| liveValueBytes | Sum of current value lengths, excluding keys/index/metadata |

Counters reconstruct on reopen and reset only where compaction removes history.
They are not lifetime operation counts and omit unreferenced files. `inspect(Path)`
requires an existing store and exclusive ownership, validates metadata and all
complete records without repairs, and reports incomplete tail bytes separately.
It does not initialize, compact or remove evidence. Opening explicitly performs
tail recovery; no salvage of corrupt complete records is implemented.

All operations are linearizable within a process, using a fair coarse read/write
lock. Readers share the read lock and use positional reads. Mutations, compaction
and close use the write lock. Close is idempotent, releases resources even when
another close fails, and later operations throw IllegalStateException. If close
races a mutation, either the mutation completes before close or it is rejected.

| Failure | Exception |
| --- | --- |
| Invalid key, value or limits | IllegalArgumentException (null Path/Options: NullPointerException) |
| Missing metadata or authoritative generation | MissingStoreException |
| Invalid format, checksum, version, framing or snapshot | CorruptStoreException |
| Store already owned by this or another process | StoreLockedException |
| Atomic compaction publication unavailable | UnsupportedCompactionException; old generation retained |
| Ambiguous mutation/compaction/read I/O | StoreException with original IOException cause; handle failed |
| Other open/close I/O failure | IOException |
| Closed/failed handle or sequence exhausted | IllegalStateException |

Storage-specific checked exceptions extend StoreException, which extends
IOException. A failed append/force/publication must not be retried on the same
handle: close and recover. An errored/unacknowledged operation may have reached
the log and survive recovery. Successful writes are durable across the tested
abrupt process termination cases; hardware power-loss guarantees are qualified
in [the format specification](storage-format.md).
