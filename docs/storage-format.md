# DurableKV storage format, version 1

All numbers are big endian. Checksums are CRC32C (accidental corruption detection,
not authentication). Format limits are 1,024 UTF-8 key bytes and 1,048,576 value
bytes. Keys must be nonempty, valid Unicode strings encoded strictly as UTF-8;
no normalization is performed. Application options may lower these limits.

## Files and authority

`LOCK` is held with an exclusive operating system FileLock for the handle lifetime.
`MANIFEST` alone selects `gen-<canonical UUID>.log`. Unreferenced generations and
temporary manifests never select state. Nonempty directories without MANIFEST
fail as missing metadata, including interrupted initialization. They require manual
inspection; opening them never silently initializes an empty replacement.

MANIFEST is exactly 40 bytes: magic `DKVMAN01` (8), version 1 (int), reserved zero
(int), UUID most/least significant longs (16), CRC32C of bytes 0..31 (int), reserved
zero (int). Missing, invalid or unsupported authoritative metadata is an error.

Generation header is exactly 56 bytes: magic `DKVLOG01` (8), version 1 (int),
header length 56 (int), generation UUID (16), snapshot sequence high-water mark
(long), snapshot record count (long), CRC32C of bytes 0..47 (int), reserved zero
(int). High-water and count are nonnegative. The first count records are a complete
snapshot: unique live PUT keys, strictly increasing original positive sequences,
each at most high-water. No partial snapshot is recoverable. Subsequent records
are mutations strictly increasing above the snapshot high-water mark. Sequence
gaps are legal. An empty snapshot retains the high-water mark after all deletions.

## Mutation records

Each record has a 28-byte header: total record length including checksums (int),
record version 1 (byte), operation PUT=1 or DELETE=2 (byte), reserved zero (short),
sequence (long), key byte length (int), value byte length (int), CRC32C of the first
24 header bytes (int). The separate header checksum is checked before trusting
lengths, so damaged length fields cannot simply disguise a complete record as a
truncated payload. Then key UTF-8
bytes, value bytes, and CRC32C of header and payload (int). DELETE value length
must be zero. Minimum record size is 33; maximum is 1,049,632. Lengths, operation,
version and sequence are checked before allocating the bounded record buffer.
Index entries hold value offsets, sizes and sequences; values use positional
reads, never a shared channel position. Values returned to callers are fresh arrays.

## Recovery and publication

Opening validates the manifest and selected generation and scans complete records.
A short final header, payload or checksum in the mutation suffix is an incomplete
append; normal open truncates it at the last valid boundary and forces the file.
`inspect` validates without modifying files and reports a recognized incomplete
tail. Invalid complete headers, lengths, versions, UTF-8, sequences or checksums
are corruption errors, never silently skipped. Snapshot truncation always fails.

Mutations under the write lock append using explicit write loops, force(true),
publish their index entry and high-water mark, then return. Uncertain I/O marks
the handle failed: callers close and reopen. An operation returning an I/O error
may survive recovery. The process crash oracle permits issued unacknowledged
operations, but requires all acknowledged operations in a legal recovered prefix.

Compaction holds the write lock, writes a separate generation sorted by original
live sequence, validates it, forces it and closes the preparation channel. It
writes and forces a unique temporary manifest, then uses same-directory
ATOMIC_MOVE with REPLACE_EXISTING to publish MANIFEST. No delete/rename fallback
exists. It then opens and activates the new generation, closes the old handle and
deletes only known obsolete generation/temp files. Unsupported atomic replacement
leaves the old authority and returns an explicit unsupported-compaction error.
Any other ambiguous publication or post-publication error fails the handle.
Recovery always follows MANIFEST even if the selected file is missing or corrupt.

Initialization uses the same prepare/force/manifest-publication protocol, with a
new UUID and empty snapshot. An interrupted pre-publication initialization is
intentionally rejected, preserving evidence. A post-publication initialization is
recoverable. Lock files are never deleted as a lock-release mechanism.

## Guarantees and boundaries

A coarse read/write lock makes supported operations linearizable; readers share
the read lock and use positional reads. Mutations, compaction and close exclude
all readers. The index grows with live key count, not value bytes. Compaction
pauses all operations and temporarily needs space for both generations.

Every successful mutation forces its own record; there is no buffered success or
group commit mode. FileChannel.force(true) is used for content and file metadata.
Directory metadata synchronization is not portable in Java, so process termination
recovery on the tested filesystem is the demonstrated guarantee. Atomic rename
alone does not prove hardware power-loss durability, which also depends on OS,
filesystem and devices. Network filesystems and multi-process access are outside
the supported configuration. Checksums do not protect against hostile modification.

API references checked before implementation: [Java 21 FileChannel](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html),
[Files](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html),
[StandardCopyOption](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/StandardCopyOption.html).
