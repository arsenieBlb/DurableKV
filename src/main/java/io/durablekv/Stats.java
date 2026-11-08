package io.durablekv;

import java.util.UUID;

/** Current generation counters, reconstructed on open. Records include snapshot and tombstones.
 * Log bytes exclude orphan generations/metadata. Live value bytes exclude keys and index overhead. */
public record Stats(UUID generation, long sequence, long liveKeys, long logBytes,
                    long records, long liveValueBytes) {}
