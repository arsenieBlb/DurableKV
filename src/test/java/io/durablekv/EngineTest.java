package io.durablekv;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    static Path directory(String label) throws IOException {
        Path root = Path.of("target", "test-data");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, label + "-");
    }

    @Test void binaryUtf8ReplacementMissingAndIdempotentDelete() throws Exception {
        Path path = directory("api");
        try (DurableKv store = DurableKv.open(path)) {
            assertTrue(store.get("missing").isEmpty());
            assertEquals(1, store.put("鍵🙂", new byte[0]));
            assertArrayEquals(new byte[0], store.get("鍵🙂").orElseThrow());
            byte[] binary = {0, -1, 37, 0, 127};
            assertEquals(2, store.put("鍵🙂", binary));
            assertArrayEquals(binary, store.get("鍵🙂").orElseThrow());
            assertEquals(3, store.delete("鍵🙂"));
            assertTrue(store.get("鍵🙂").isEmpty());
            assertEquals(4, store.delete("鍵🙂"));
        }
        try (DurableKv reopened = DurableKv.open(path)) {
            assertTrue(reopened.get("鍵🙂").isEmpty());
            assertEquals(5, reopened.put("next", new byte[]{1}));
        }
    }

    @Test void mutableArraysNeverEscapeAndKeysAreNotNormalized() throws Exception {
        try (DurableKv store = DurableKv.open(directory("defensive"))) {
            byte[] input = {1, 2, 3};
            store.put("é", input);
            input[0] = 99;
            byte[] first = store.get("é").orElseThrow();
            assertArrayEquals(new byte[]{1, 2, 3}, first);
            first[1] = 99;
            assertArrayEquals(new byte[]{1, 2, 3}, store.get("é").orElseThrow());
            assertTrue(store.get("e\u0301").isEmpty());
            store.put("e\u0301", new byte[]{4});
            assertArrayEquals(new byte[]{1, 2, 3}, store.get("é").orElseThrow());
        }
    }

    @Test void validatesEncodedBoundariesBeforeMutatingStore() throws Exception {
        try (DurableKv store = DurableKv.open(directory("limits"))) {
            String maximumKey = "é".repeat(512);
            byte[] maximumValue = new byte[1024 * 1024];
            Arrays.fill(maximumValue, (byte) 0xa5);
            assertEquals(1, store.put(maximumKey, maximumValue));
            assertArrayEquals(maximumValue, store.get(maximumKey).orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> store.put("é".repeat(513), new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> store.put("oversized", new byte[1024 * 1024 + 1]));
            assertThrows(IllegalArgumentException.class, () -> store.put("", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> store.get(""));
            assertThrows(IllegalArgumentException.class, () -> store.delete(""));
            assertThrows(IllegalArgumentException.class, () -> store.put("\ud800", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> store.put(null, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> store.put("null", null));
            assertEquals(2, store.delete("absent"));
        }
    }

    @Test void configurableLimitsAndInvalidOptions() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Options(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Options(1025, 1));
        assertThrows(IllegalArgumentException.class, () -> new Options(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new Options(1, 1024 * 1024 + 1));
        try (DurableKv store = DurableKv.open(directory("options"), new Options(4, 3))) {
            store.put("éé", new byte[]{1, 2, 3});
            assertThrows(IllegalArgumentException.class, () -> store.put("ééé", new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> store.put("a", new byte[4]));
        }
    }

    @Test void compactionKeepsStateAndHighWaterAfterEveryKeyIsDeleted() throws Exception {
        Path path = directory("compact");
        try (DurableKv store = DurableKv.open(path)) {
            store.put("live", "old".getBytes(StandardCharsets.UTF_8));
            store.put("live", new byte[1024 * 1024]);
            store.put("gone", new byte[]{4});
            store.delete("gone");
            store.compact();
            assertEquals(1024 * 1024, store.get("live").orElseThrow().length);
            assertTrue(store.get("gone").isEmpty());
            assertEquals(5, store.delete("live"));
            store.compact();
        }
        try (DurableKv store = DurableKv.open(path)) {
            assertTrue(store.get("live").isEmpty());
            assertTrue(store.get("gone").isEmpty());
            assertEquals(6, store.put("after-empty-snapshot", new byte[]{6}));
            store.compact();
            assertEquals(7, store.delete("after-empty-snapshot"));
        }
    }

    @Test void closeIsIdempotentAndAllOperationsRejectClosedHandle() throws Exception {
        DurableKv store = DurableKv.open(directory("closed"));
        store.close();
        store.close();
        assertThrows(IllegalStateException.class, () -> store.get("k"));
        assertThrows(IllegalStateException.class, () -> store.put("k", new byte[0]));
        assertThrows(IllegalStateException.class, () -> store.delete("k"));
        assertThrows(IllegalStateException.class, store::compact);
        assertThrows(IllegalStateException.class, store::stats);
    }

    @Test void seededModelSequencesSurviveRepeatedReopenAndCompaction() throws Exception {
        long[] seeds = {0, 1, 7, 42, 1024, 0x5eed, 99887766};
        for (long seed : seeds) {
            Path path = directory("model-" + seed);
            ReferenceModel model = new ReferenceModel();
            Random random = new Random(seed);
            DurableKv store = DurableKv.open(path);
            try {
                for (int step = 0; step < 150; step++) {
                    String key = "key-" + random.nextInt(12);
                    int operation = random.nextInt(10);
                    if (operation < 5) {
                        byte[] value = new byte[random.nextInt(129)];
                        random.nextBytes(value);
                        assertEquals(model.put(key, value), store.put(key, value), "seed=" + seed + " step=" + step);
                    } else if (operation < 8) {
                        assertEquals(model.delete(key), store.delete(key), "seed=" + seed + " step=" + step);
                    } else {
                        assertTrue(ReferenceModel.equal(model.get(key), store.get(key)), "seed=" + seed + " step=" + step);
                    }
                    if (step % 31 == 0) store.compact();
                    if (step % 47 == 0) {
                        store.close();
                        store = DurableKv.open(path);
                    }
                }
                for (int key = 0; key < 12; key++) {
                    assertTrue(ReferenceModel.equal(model.get("key-" + key), store.get("key-" + key)), "seed=" + seed);
                }
            } finally { store.close(); }
        }
    }

    @Test void foreignNonemptyDirectoryIsNeverInitialized() throws Exception {
        Path path = directory("foreign");
        Files.writeString(path.resolve("unrelated.txt"), "preserve me");
        assertThrows(MissingStoreException.class, () -> DurableKv.open(path));
        assertEquals("preserve me", Files.readString(path.resolve("unrelated.txt")));
    }

    @Test void statsDescribeOnlyMaintainedCurrentGenerationCounters() throws Exception {
        Path path = directory("stats");
        Stats compacted;
        try (DurableKv store = DurableKv.open(path)) {
            assertEquals(0, store.stats().sequence());
            assertEquals(0, store.stats().records());
            assertEquals(56, store.stats().logBytes());
            store.put("key", new byte[20]);
            store.put("key", new byte[2]);
            store.delete("missing");
            Stats before = store.stats();
            assertEquals(3, before.sequence());
            assertEquals(1, before.liveKeys());
            assertEquals(3, before.records());
            assertEquals(2, before.liveValueBytes());
            assertEquals(Files.size(FramingTest.generation(path)), before.logBytes());
            store.compact();
            compacted = store.stats();
            assertNotEquals(before.generation(), compacted.generation());
            assertEquals(3, compacted.sequence());
            assertEquals(1, compacted.liveKeys());
            assertEquals(1, compacted.records());
            assertEquals(2, compacted.liveValueBytes());
            assertTrue(compacted.logBytes() < before.logBytes());
        }
        try (DurableKv store = DurableKv.open(path)) { assertEquals(compacted, store.stats()); }
    }
}
