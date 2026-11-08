package io.durablekv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class IoFailureTest {
    @Test void shortReadsAndWritesAreRetriedUntilComplete() throws Exception {
        Io shortIo = new Io() {
            @Override public int read(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
                int original = buffer.limit();
                buffer.limit(Math.min(original, buffer.position() + 3));
                try { return Io.super.read(channel, buffer, position); }
                finally { buffer.limit(original); }
            }
            @Override public int write(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
                int original = buffer.limit();
                buffer.limit(Math.min(original, buffer.position() + 2));
                try { return Io.super.write(channel, buffer, position); }
                finally { buffer.limit(original); }
            }
        };
        Path path = EngineTest.directory("short-io");
        try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), shortIo, Hooks.NONE)) {
            byte[] value = new byte[1003];
            java.util.Arrays.fill(value, (byte) 93);
            assertEquals(1, store.put("key-🙂", value));
            assertArrayEquals(value, store.get("key-🙂").orElseThrow());
            store.compact();
        }
        try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), shortIo, Hooks.NONE)) {
            assertEquals(1003, store.get("key-🙂").orElseThrow().length);
            assertEquals(2, store.delete("key-🙂"));
        }
    }

    @Test void forceFailureReturnsNoSuccessAndPoisonsHandleUntilReopen() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        Io io = new Io() {
            @Override public void force(FileChannel channel) throws IOException {
                if (fail.get()) throw new IOException("injected force failure");
                Io.super.force(channel);
            }
        };
        Path path = EngineTest.directory("force-failure");
        try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), io, Hooks.NONE)) {
            assertEquals(1, store.put("acknowledged", new byte[]{1}));
            fail.set(true);
            assertThrows(IOException.class, () -> store.put("uncertain", new byte[]{2}));
            assertFailed(store);
        }
        try (DurableKv store = DurableKv.open(path)) {
            assertArrayEquals(new byte[]{1}, store.get("acknowledged").orElseThrow());
            // Complete issued writes are legal even when the durability call reported failure.
            assertArrayEquals(new byte[]{2}, store.get("uncertain").orElseThrow());
            assertEquals(3, store.delete("uncertain"));
        }
    }

    @Test void partialAppendFailureIsRecoveredAsAnIncompleteTail() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        Hooks hooks = point -> {
            if (fail.get() && point.equals("append.partial")) throw new IOException("injected append failure");
        };
        Path path = EngineTest.directory("partial-failure");
        try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), Io.SYSTEM, hooks)) {
            store.put("acknowledged", new byte[]{1});
            fail.set(true);
            assertThrows(IOException.class, () -> store.put("uncertain", new byte[100]));
            assertFailed(store);
        }
        try (DurableKv store = DurableKv.open(path)) {
            assertArrayEquals(new byte[]{1}, store.get("acknowledged").orElseThrow());
            assertTrue(store.get("uncertain").isEmpty());
            assertEquals(2, store.put("after", new byte[]{3}));
        }
    }

    @Test void completedAppendFailureMaySurviveButNeverAllowsFurtherLiveOperations() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        Hooks hooks = point -> {
            if (fail.get() && point.equals("append.complete")) throw new IOException("completed write uncertainty");
        };
        Path path = EngineTest.directory("complete-failure");
        try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), Io.SYSTEM, hooks)) {
            fail.set(true);
            assertThrows(IOException.class, () -> store.put("issued", new byte[]{3}));
            assertFailed(store);
        }
        try (DurableKv store = DurableKv.open(path)) {
            assertArrayEquals(new byte[]{3}, store.get("issued").orElseThrow());
        }
    }

    @Test void unsupportedAtomicReplacementPreservesOldManifestAndUsableHandle() throws Exception {
        AtomicBoolean unsupported = new AtomicBoolean();
        Io io = new Io() {
            @Override public void atomicMove(Path source, Path target) throws IOException {
                if (unsupported.get()) throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected");
                Io.super.atomicMove(source, target);
            }
        };
        Path path = EngineTest.directory("unsupported-move");
        try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), io, Hooks.NONE)) {
            store.put("key", new byte[]{1});
            byte[] authority = Files.readAllBytes(path.resolve("MANIFEST"));
            unsupported.set(true);
            assertThrows(UnsupportedCompactionException.class, store::compact);
            assertArrayEquals(authority, Files.readAllBytes(path.resolve("MANIFEST")));
            assertArrayEquals(new byte[]{1}, store.get("key").orElseThrow());
            assertEquals(2, store.put("later", new byte[]{2}));
        }
        try (DurableKv store = DurableKv.open(path)) {
            assertArrayEquals(new byte[]{2}, store.get("later").orElseThrow());
        }
    }

    @Test void ambiguousPublicationFailurePoisonsHandleAndRecoveryFollowsCommittedManifest() throws Exception {
        for (boolean publishBeforeThrow : new boolean[]{false, true}) {
            AtomicBoolean fail = new AtomicBoolean();
            Io io = new Io() {
                @Override public void atomicMove(Path source, Path target) throws IOException {
                    if (fail.get()) {
                        if (publishBeforeThrow) Io.super.atomicMove(source, target);
                        throw new IOException("injected ambiguous publication");
                    }
                    Io.super.atomicMove(source, target);
                }
            };
            Path path = EngineTest.directory("ambiguous-publication");
            try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), io, Hooks.NONE)) {
                store.put("key", new byte[]{7});
                store.put("gone", new byte[]{8});
                store.delete("gone");
                fail.set(true);
                assertThrows(IOException.class, store::compact);
                assertFailed(store);
            }
            try (DurableKv store = DurableKv.open(path)) {
                assertArrayEquals(new byte[]{7}, store.get("key").orElseThrow());
                assertTrue(store.get("gone").isEmpty());
                assertEquals(4, store.put("after", new byte[]{9}));
            }
        }
    }

    @Test void everyPostPublicationFailureRequiresRecoveryEvenWhenExceptionNamesUnsupportedMove() throws Exception {
        for (String point : new String[]{"compact.published", "compact.activated", "compact.cleaned"}) {
            AtomicBoolean fail = new AtomicBoolean();
            Hooks hooks = hit -> {
                if (fail.get() && hit.equals(point)) {
                    if (point.equals("compact.published"))
                        throw new AtomicMoveNotSupportedException("already", "published", "postpublication injected failure");
                    throw new IOException("injected error at " + point);
                }
            };
            Path path = EngineTest.directory("postpublication");
            try (DurableKv store = DurableKv.openForTesting(path, Options.defaults(), Io.SYSTEM, hooks)) {
                store.put("live", new byte[]{1});
                store.put("dead", new byte[]{2});
                store.delete("dead");
                fail.set(true);
                assertThrows(IOException.class, store::compact);
                assertFailed(store);
            }
            try (DurableKv store = DurableKv.open(path)) {
                assertArrayEquals(new byte[]{1}, store.get("live").orElseThrow());
                assertTrue(store.get("dead").isEmpty());
                assertEquals(4, store.delete("absent"));
            }
        }
    }

    @Test void failedInitializationAlwaysReleasesProcessLockAndPreservesEvidence() throws Exception {
        for (String point : new String[]{"init.generation.created", "init.generation.forced", "init.manifest.forced", "init.published"}) {
            Path path = EngineTest.directory("failed-init");
            Hooks hooks = hit -> { if (hit.equals(point)) throw new IOException("injected initialization failure"); };
            assertThrows(IOException.class, () -> DurableKv.openForTesting(path, Options.defaults(), Io.SYSTEM, hooks));
            try (FileChannel channel = FileChannel.open(path.resolve("LOCK"), StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                assertNotNull(lock, "failed initialization leaked process ownership at " + point);
            }
            if (point.equals("init.published")) {
                try (DurableKv store = DurableKv.open(path)) { assertEquals(1, store.put("after", new byte[0])); }
            } else {
                assertThrows(MissingStoreException.class, () -> DurableKv.open(path));
            }
        }
    }

    @Test void failedRecoveryForceReleasesResourcesBeforeAnotherOpen() throws Exception {
        Path path = EngineTest.directory("recovery-force-failure");
        try (DurableKv store = DurableKv.open(path)) { store.put("kept", new byte[]{1}); }
        Files.write(FramingTest.generation(path), new byte[]{1, 2}, StandardOpenOption.APPEND);
        Io io = new Io() {
            @Override public void force(FileChannel channel) throws IOException { throw new IOException("repair force failure"); }
        };
        assertThrows(IOException.class, () -> DurableKv.openForTesting(path, Options.defaults(), io, Hooks.NONE));
        try (DurableKv store = DurableKv.open(path)) {
            assertArrayEquals(new byte[]{1}, store.get("kept").orElseThrow());
        }
    }

    @Test void readerOverlappingBlockedForceSeesMutationOnlyAfterDurabilityAndPublication() throws Exception {
        AtomicBoolean block = new AtomicBoolean();
        CountDownLatch forcing = new CountDownLatch(1);
        CountDownLatch finishForce = new CountDownLatch(1);
        Io io = new Io() {
            @Override public void force(FileChannel channel) throws IOException {
                if (block.get()) {
                    forcing.countDown();
                    try {
                        if (!finishForce.await(10, TimeUnit.SECONDS)) throw new IOException("force gate timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                }
                Io.super.force(channel);
            }
        };
        try (DurableKv store = DurableKv.openForTesting(EngineTest.directory("force-ordering"), Options.defaults(), io, Hooks.NONE);
             var threads = Executors.newFixedThreadPool(2)) {
            block.set(true);
            var writer = threads.submit(() -> store.put("key", new byte[]{4}));
            assertTrue(forcing.await(10, TimeUnit.SECONDS));
            CountDownLatch readerInvoked = new CountDownLatch(1);
            var reader = threads.submit(() -> {
                readerInvoked.countDown();
                return store.get("key");
            });
            assertTrue(readerInvoked.await(10, TimeUnit.SECONDS));
            assertFalse(writer.isDone(), "a mutation must not succeed before force completes");
            assertFalse(reader.isDone(), "a reader must not bypass the in-flight mutation lock");
            finishForce.countDown();
            assertEquals(1L, writer.get(10, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{4}, reader.get(10, TimeUnit.SECONDS).orElseThrow());
        } finally { finishForce.countDown(); }
    }

    private static void assertFailed(DurableKv store) {
        assertThrows(IllegalStateException.class, () -> store.get("any"));
        assertThrows(IllegalStateException.class, () -> store.put("any", new byte[0]));
        assertThrows(IllegalStateException.class, () -> store.delete("any"));
        assertThrows(IllegalStateException.class, store::compact);
        assertThrows(IllegalStateException.class, store::stats);
    }
}
