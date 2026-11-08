package io.durablekv;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

class FramingTest {
    static Path generation(Path directory) throws Exception {
        ByteBuffer manifest = ByteBuffer.wrap(Files.readAllBytes(directory.resolve("MANIFEST")))
                .order(ByteOrder.BIG_ENDIAN);
        UUID id = new UUID(manifest.getLong(16), manifest.getLong(24));
        return directory.resolve("gen-" + id + ".log");
    }

    private static Path oneRecord(String label) throws Exception {
        Path path = EngineTest.directory(label);
        try (DurableKv store = DurableKv.open(path)) {
            store.put("kept", new byte[]{7});
        }
        return path;
    }

    @Test void everyPossibleIncompleteFinalRecordBoundaryRecoversOnlyCompleteRecords() throws Exception {
        // Cover short framing header, short key/value payload and short checksum separately.
        int[] tailLengths = {1, 4, 23, 24, 27, 28, 29, 32, 33, 34, 35, 36};
        for (int tailLength : tailLengths) {
            Path path = oneRecord("tail-" + tailLength);
            Path generation = generation(path);
            long boundary = Files.size(generation);
            try (DurableKv store = DurableKv.open(path)) {
                store.put("tail", new byte[]{8});
            }
            byte[] complete = Files.readAllBytes(generation);
            Files.write(generation, java.util.Arrays.copyOf(complete, (int) boundary + tailLength));
            long damagedSize = Files.size(generation);
            assertEquals(tailLength, DurableKv.inspect(path).incompleteTailBytes());
            assertEquals(damagedSize, Files.size(generation), "inspect must be read only");
            try (DurableKv store = DurableKv.open(path)) {
                assertArrayEquals(new byte[]{7}, store.get("kept").orElseThrow());
                assertTrue(store.get("tail").isEmpty());
                assertEquals(boundary, Files.size(generation));
                assertEquals(2, store.put("after", new byte[]{9}));
            }
        }
    }

    @Test void completeChecksumFailureIsCorruptionAndDoesNotModifyEvidence() throws Exception {
        Path path = oneRecord("crc");
        Path generation = generation(path);
        byte[] bytes = Files.readAllBytes(generation);
        bytes[bytes.length - 1] ^= 0x40;
        Files.write(generation, bytes);
        assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
        assertThrows(CorruptStoreException.class, () -> DurableKv.inspect(path));
        assertArrayEquals(bytes, Files.readAllBytes(generation));
        // A failed open must not leak the ownership lock.
        bytes[bytes.length - 1] ^= 0x40;
        Files.write(generation, bytes);
        try (DurableKv store = DurableKv.open(path)) {
            assertArrayEquals(new byte[]{7}, store.get("kept").orElseThrow());
        }
    }

    @Test void middleCorruptionIsNeverTreatedAsRecoverableTail() throws Exception {
        Path path = oneRecord("middle-crc");
        try (DurableKv store = DurableKv.open(path)) { store.put("last", new byte[]{2}); }
        Path generation = generation(path);
        byte[] bytes = Files.readAllBytes(generation);
        bytes[56 + 28] ^= 1;
        Files.write(generation, bytes);
        assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
        assertArrayEquals(bytes, Files.readAllBytes(generation));
    }

    @Test void impossibleLengthsAndUnsupportedRecordVersionsAreRejectedBeforeAllocation() throws Exception {
        int[][] mutations = {
                {0, Integer.MAX_VALUE}, {0, 0}, {16, Integer.MAX_VALUE},
                {16, -1}, {20, Integer.MAX_VALUE}, {20, -1}, {16, 0}
        };
        for (int[] mutation : mutations) {
            Path path = oneRecord("bad-length");
            Path generation = generation(path);
            byte[] bytes = Files.readAllBytes(generation);
            ByteBuffer.wrap(bytes).putInt(56 + mutation[0], mutation[1]);
            rewriteRecordHeaderChecksum(bytes, 56);
            Files.write(generation, bytes);
            assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
        }
        Path path = oneRecord("record-version");
        Path generation = generation(path);
        byte[] bytes = Files.readAllBytes(generation);
        bytes[56 + 4] = 99;
        rewriteRecordHeaderChecksum(bytes, 56);
        Files.write(generation, bytes);
        assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
    }

    @Test void corruptLengthsCannotMasqueradeAsInterruptedAppendAndDiscardCompleteRecord() throws Exception {
        Path path = oneRecord("enlarged-lengths");
        Path generation = generation(path);
        byte[] bytes = Files.readAllBytes(generation);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        // Keep total and value lengths mutually consistent while making a complete issued
        // record look physically short. The separate framing checksum must reject it.
        buffer.putInt(56, buffer.getInt(56) + 500);
        buffer.putInt(56 + 20, buffer.getInt(56 + 20) + 500);
        Files.write(generation, bytes);
        assertThrows(CorruptStoreException.class, () -> DurableKv.inspect(path));
        assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
        assertArrayEquals(bytes, Files.readAllBytes(generation));
    }

    @Test void generationAndManifestMetadataAreValidated() throws Exception {
        for (boolean manifest : new boolean[]{false, true}) {
            Path path = oneRecord("metadata-version");
            Path file = manifest ? path.resolve("MANIFEST") : generation(path);
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer.wrap(bytes).putInt(8, 99);
            rewriteChecksum(bytes, manifest ? 32 : 48);
            Files.write(file, bytes);
            assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
        }
        Path path = oneRecord("metadata-checksum");
        byte[] bytes = Files.readAllBytes(path.resolve("MANIFEST"));
        bytes[32] ^= 1;
        Files.write(path.resolve("MANIFEST"), bytes);
        assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
    }

    @Test void validChecksumDoesNotMakeInvalidUtf8OrSequenceLegal() throws Exception {
        for (boolean key : new boolean[]{true, false}) {
            Path path = oneRecord(key ? "utf8" : "sequence");
            Path generation = generation(path);
            byte[] bytes = Files.readAllBytes(generation);
            if (key) bytes[56 + 28] = (byte) 0xff;
            else {
                ByteBuffer.wrap(bytes).putLong(56 + 8, 0);
                rewriteRecordHeaderChecksum(bytes, 56);
            }
            CRC32C crc = new CRC32C();
            crc.update(bytes, 56, bytes.length - 56 - 4);
            ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) crc.getValue());
            Files.write(generation, bytes);
            assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));
        }
    }

    @Test void snapshotTruncationAndMissingAuthoritativeGenerationNeverRollBack() throws Exception {
        Path path = oneRecord("snapshot-tail");
        try (DurableKv store = DurableKv.open(path)) { store.compact(); }
        Path generation = generation(path);
        byte[] bytes = Files.readAllBytes(generation);
        Files.write(generation, java.util.Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(CorruptStoreException.class, () -> DurableKv.open(path));

        Path missing = oneRecord("missing-authority");
        Path selected = generation(missing);
        Files.move(selected, missing.resolve("gen-" + UUID.randomUUID() + ".log"));
        assertThrows(MissingStoreException.class, () -> DurableKv.open(missing));
    }

    @Test void orphanFilesNeverOverrideAuthorityAndForeignFilesSurviveCleanup() throws Exception {
        Path path = oneRecord("orphans");
        Files.write(path.resolve("gen-" + UUID.randomUUID() + ".log"), new byte[]{1, 2, 3});
        Files.write(path.resolve("manifest-uncommitted.tmp"), new byte[]{1, 2, 3});
        Path foreign = path.resolve("my-important-file.txt");
        Files.writeString(foreign, "keep");
        try (DurableKv store = DurableKv.open(path)) {
            assertArrayEquals(new byte[]{7}, store.get("kept").orElseThrow());
            store.compact();
        }
        assertEquals("keep", Files.readString(foreign));
    }

    static void rewriteChecksum(byte[] bytes, int offset) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, offset);
        ByteBuffer.wrap(bytes).putInt(offset, (int) crc.getValue());
    }

    private static void rewriteRecordHeaderChecksum(byte[] bytes, int recordOffset) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, recordOffset, 24);
        ByteBuffer.wrap(bytes).putInt(recordOffset + 24, (int) crc.getValue());
    }
}
