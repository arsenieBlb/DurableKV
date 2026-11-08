package io.durablekv;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real parent/child JVM tests: the OS terminates a child that never closes its store. */
class CrashTest {
    private static final List<String> APPEND_POINTS = List.of("append.partial", "append.complete",
            "append.forced", "append.indexed", "response.before", "response.acknowledged");
    private static final List<String> PUBLICATION_STEPS = List.of("generation.created",
            "generation.written", "generation.validated", "generation.forced", "manifest.written",
            "manifest.forced", "before.publish", "published", "activated", "cleaned");
    private static final Path EVIDENCE = Path.of("target", "crash-evidence");

    @TestFactory
    Stream<DynamicTest> everyAppendAndResponseBoundary() {
        return APPEND_POINTS.stream().map(point -> DynamicTest.dynamicTest(point, () -> {
            Path dir = directory("deterministic-" + point);
            List<Op> operations = new ArrayList<>();
            try (Child child = Child.start(dir, point, 2)) {
                child.await("READY");
                Op first = new Op("unique-1", new byte[]{0, -1, 42});
                operations.add(first);
                child.issue(1, first);
                child.await("ACK 1 ");
                Op second = new Op("unique-2", new byte[]{8, 7, 6});
                operations.add(second);
                child.issue(2, second);
                child.await("PAUSE " + point);
                child.kill();
                assertLegalPrefix(dir, operations, child.acknowledged(), child,
                        "deterministic-" + point, "none");
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> thirtyRecordedRandomSeeds() {
        return IntStream.range(0, 30).mapToObj(number -> {
            long seed = 0xD00AB1E000L + number;
            return DynamicTest.dynamicTest("seed=" + seed, () -> {
                Random random = new Random(seed);
                int acknowledgedBeforeKill = 3 + random.nextInt(12);
                String point = APPEND_POINTS.get(number % APPEND_POINTS.size());
                List<Op> operations = new ArrayList<>();
                Path dir = directory("seed-" + seed);
                try (Child child = Child.start(dir, point, acknowledgedBeforeKill + 1)) {
                    child.await("READY");
                    for (int i = 1; i <= acknowledgedBeforeKill + 1; i++) {
                        byte[] value = null;
                        if (random.nextInt(3) != 0) {
                            value = new byte[random.nextInt(64)];
                            random.nextBytes(value);
                        }
                        Op operation = new Op("k" + random.nextInt(5), value);
                        operations.add(operation);
                        child.issue(i, operation);
                        if (i <= acknowledgedBeforeKill) child.await("ACK " + i + " ");
                    }
                    child.await("PAUSE " + point);
                    child.kill();
                    assertLegalPrefix(dir, operations, child.acknowledged(), child,
                            "seed-" + seed, Long.toString(seed));
                }
            });
        });
    }

    @TestFactory
    Stream<DynamicTest> thirtyUnpausedWorkloadsKilledAtSeededRandomIssuedOperations() {
        return IntStream.range(0, 30).mapToObj(number -> {
            long seed = 0xC2A50000L + number;
            return DynamicTest.dynamicTest("unpaused-seed=" + seed, () -> {
                Random random = new Random(seed);
                int killAfterIssued = 3 + random.nextInt(38);
                List<Op> operations = new ArrayList<>();
                Path dir = directory("unpaused-seed-" + seed);
                try (Child child = Child.start(dir, "none", 1)) {
                    child.await("READY");
                    // Prepare a deterministic workload; a separate producer keeps the input pipe
                    // from delaying the parent's kill until most writes have already finished.
                    for (int id = 1; id <= 64; id++) {
                        byte[] value = null;
                        if (random.nextInt(3) != 0) {
                            value = new byte[random.nextInt(4096)];
                            random.nextBytes(value);
                        }
                        Op operation = new Op("random-key-" + random.nextInt(8), value);
                        operations.add(operation);
                    }
                    try (ExecutorService producer = Executors.newSingleThreadExecutor()) {
                        var writing = producer.submit(() -> {
                            for (int id = 1; id <= operations.size(); id++) child.issue(id, operations.get(id - 1));
                            return null;
                        });
                        // No hook pauses the child. It may progress beyond this event before the
                        // OS delivers termination; the oracle uses all observed issued/ACK events.
                        child.await("ISSUED " + killAfterIssued);
                        child.kill();
                        try { writing.get(10, TimeUnit.SECONDS); }
                        catch (ExecutionException terminatedPipe) {
                            assertInstanceOf(java.io.IOException.class, terminatedPipe.getCause());
                        }
                    }
                    // Drain all pipe output after termination: an ACK already emitted must count.
                    List<Op> actuallyIssued = new ArrayList<>(operations.subList(0, child.issued()));
                    assertLegalPrefix(dir, actuallyIssued, child.acknowledged(), child,
                            "unpaused-seed-" + seed, Long.toString(seed));
                }
            });
        });
    }

    @TestFactory
    Stream<DynamicTest> initializationInterruptedAtEveryPublicationBoundary() {
        return PUBLICATION_STEPS.stream().map(step -> DynamicTest.dynamicTest("init." + step, () -> {
            Path dir = directory("init-" + step);
            try (Child child = Child.start(dir, "init." + step, 1)) {
                child.await("PAUSE init." + step);
                child.kill();
                boolean published = List.of("published", "activated", "cleaned").contains(step);
                if (published) {
                    try (DurableKv recovered = DurableKv.open(dir)) {
                        assertEquals(0, recovered.stats().sequence());
                        assertEquals(0, recovered.stats().liveKeys());
                    }
                } else {
                    assertFalse(Files.exists(dir.resolve("MANIFEST")));
                    assertThrows(MissingStoreException.class, () -> DurableKv.open(dir));
                    // A failed open must release its lock and must not remove the evidence.
                    assertThrows(MissingStoreException.class, () -> DurableKv.open(dir));
                    assertTrue(Files.exists(dir.resolve("LOCK")));
                }
                save("init-" + step, "seed=none\nexpected=" + (published ? "empty committed store" :
                        "missing metadata; preserved files") + "\n" + child.transcript());
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> compactionInterruptedAtEveryPublicationBoundary() {
        return PUBLICATION_STEPS.stream().map(step -> DynamicTest.dynamicTest("compact." + step, () -> {
            Path dir = directory("compact-" + step);
            byte[] nearLimit = new byte[Options.defaults().maxValueBytes()];
            Arrays.fill(nearLimit, (byte) 127);
            long highWater;
            byte[] originalManifest;
            try (DurableKv store = DurableKv.open(dir)) {
                store.put("replace", new byte[]{1});
                store.put("replace", new byte[]{2});
                store.put("deleted", new byte[]{3});
                store.delete("deleted");
                store.put("empty", new byte[0]);
                highWater = store.put("large", nearLimit);
            }
            originalManifest = Files.readAllBytes(dir.resolve("MANIFEST"));
            try (Child child = Child.start(dir, "compact." + step, 1)) {
                child.await("READY");
                child.send("1 COMPACT");
                child.await("PAUSE compact." + step);
                child.kill();
                boolean published = List.of("published", "activated", "cleaned").contains(step);
                assertEquals(published, !Arrays.equals(originalManifest,
                        Files.readAllBytes(dir.resolve("MANIFEST"))), "Manifest authority at " + step);
                try (DurableKv store = DurableKv.open(dir)) {
                    assertEquals(highWater, store.stats().sequence());
                    assertArrayEquals(new byte[]{2}, store.get("replace").orElseThrow());
                    assertTrue(store.get("deleted").isEmpty());
                    assertArrayEquals(new byte[0], store.get("empty").orElseThrow());
                    assertArrayEquals(nearLimit, store.get("large").orElseThrow());
                    assertEquals(highWater + 1, store.put("after-recovery", new byte[]{4}));
                }
                save("compact-" + step, "seed=none\nauthority=" + (published ? "new" : "old") +
                        "\nhighWater=" + highWater + "\n" + child.transcript());
            }
        }));
    }

    @Test
    void lockRejectsASecondRealProcessAndIsReleasedAfterTermination() throws Exception {
        Path dir = directory("process-lock");
        try (Child owner = Child.start(dir, "none", 1)) {
            owner.await("READY");
            try (Child contender = Child.start(dir, "none", 1)) {
                contender.await("StoreLockedException");
                assertTrue(contender.process.waitFor(20, TimeUnit.SECONDS));
                assertNotEquals(0, contender.process.exitValue());
                save("process-lock-contender", contender.transcript());
            }
            owner.issue(1, new Op("survives-owner", new byte[]{9}));
            owner.await("ACK 1 ");
            owner.kill();
            try (DurableKv recovered = DurableKv.open(dir)) {
                assertArrayEquals(new byte[]{9}, recovered.get("survives-owner").orElseThrow());
            }
            try (Child next = Child.start(dir, "none", 1)) {
                next.await("READY");
                next.send("1 CLOSE");
                next.await("CLOSED");
                assertTrue(next.process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, next.process.exitValue());
                save("process-lock", owner.transcript() + "\nreopened-second-child:\n" + next.transcript());
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> allDeletedCompactionRetainsSequenceAcrossEveryInterruption() {
        return PUBLICATION_STEPS.stream().map(step -> DynamicTest.dynamicTest("all-deleted." + step, () -> {
            Path dir = directory("all-deleted-" + step);
            long highWater;
            try (DurableKv store = DurableKv.open(dir)) {
                store.put("gone", new byte[]{1});
                store.delete("gone");
                highWater = store.delete("already-missing");
            }
            try (Child child = Child.start(dir, "compact." + step, 1)) {
                child.await("READY");
                child.send("1 COMPACT");
                child.await("PAUSE compact." + step);
                child.kill();
                try (DurableKv store = DurableKv.open(dir)) {
                    assertEquals(0, store.stats().liveKeys());
                    assertEquals(highWater, store.stats().sequence());
                    assertTrue(store.get("gone").isEmpty());
                    assertTrue(store.get("already-missing").isEmpty());
                    assertEquals(highWater + 1, store.put("new", new byte[]{2}));
                }
                save("all-deleted-" + step, "seed=none\nhighWater=" + highWater + "\n" + child.transcript());
            }
        }));
    }

    private static void assertLegalPrefix(Path dir, List<Op> issued, int acknowledged, Child child,
                                           String evidenceName, String seed) throws Exception {
        StringBuilder evidence = new StringBuilder("seed=").append(seed)
                .append("\nacknowledged=").append(acknowledged).append("\nissued=").append(issued.size())
                .append("\n").append(child.transcript());
        save(evidenceName, evidence + "\nstatus=checking recovery\n");
        try (DurableKv recovered = DurableKv.open(dir)) {
            Map<String, String> actual = new HashMap<>();
            for (Op operation : issued) recovered.get(operation.key()).ifPresent(value ->
                    actual.put(operation.key(), Base64.getEncoder().encodeToString(value)));
            assertEquals(actual.size(), recovered.stats().liveKeys());
            long sequence = recovered.stats().sequence();
            Map<String, String> candidate = new HashMap<>();
            int matched = -1;
            for (int prefix = 0; prefix <= issued.size(); prefix++) {
                if (prefix >= acknowledged && prefix == sequence && candidate.equals(actual)) matched = prefix;
                if (prefix < issued.size()) {
                    Op operation = issued.get(prefix);
                    if (operation.value() == null) candidate.remove(operation.key());
                    else candidate.put(operation.key(), Base64.getEncoder().encodeToString(operation.value()));
                }
            }
            evidence.append("\nrecoveredSequence=").append(sequence).append("\nmatchedPrefix=").append(matched)
                    .append("\nrecovered=").append(actual).append("\n");
            save(evidenceName, evidence.toString());
            assertTrue(matched >= acknowledged,
                    "Recovered state is not an issued prefix containing all acknowledgements: " + evidence);
        }
    }

    private static Path directory(String label) throws Exception {
        Path root = Path.of("target", "test-data", "crash").toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, label.replace('.', '-') + "-");
    }

    private static void save(String name, String content) throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve(name + ".txt"), content, StandardCharsets.UTF_8);
    }

    private record Op(String key, byte[] value) {
        String command(int id) {
            return value == null ? id + " DELETE " + key : id + " PUT " + key + " " +
                    Base64.getEncoder().encodeToString(value);
        }
    }

    private static final class Child implements AutoCloseable {
        private static final String END = "<EOF>";
        private final Process process;
        private final Path directory;
        private final BufferedWriter input;
        private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        private final List<String> transcript = Collections.synchronizedList(new ArrayList<>());
        private int acknowledged;
        private int issued;

        private Child(Process process, Path directory) {
            this.process = process;
            this.directory = directory;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) output.add(line);
                } catch (Exception exception) {
                    output.add("READER_ERROR " + exception);
                } finally {
                    output.add(END);
                }
            }, "crash-child-output-" + process.pid());
            reader.setDaemon(true);
            reader.start();
        }

        static Child start(Path dir, String hook, int occurrence) throws Exception {
            String executable = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            return new Child(new ProcessBuilder(executable, "-Xmx64m", "-cp", classpath,
                    CrashProcess.class.getName(), dir.toString(), hook, Integer.toString(occurrence))
                    .redirectErrorStream(true).start(), dir);
        }

        void send(String command) throws Exception {
            transcript.add("PARENT> " + command);
            input.write(command);
            input.newLine();
            input.flush();
        }

        void issue(int id, Op operation) throws Exception { send(operation.command(id)); }

        void await(String expected) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                String line = remaining <= 0 ? null : output.poll(remaining, TimeUnit.NANOSECONDS);
                assertNotNull(line, "Timed out awaiting " + expected + "\n" + transcript());
                observe(line);
                if (line.contains(expected)) return;
                assertNotEquals(END, line, "Child exited awaiting " + expected + "\n" + transcript());
            }
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Child was not terminated");
            transcript.add("PARENT> destroyForcibly; exit=" + process.exitValue());
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (true) {
                String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(line, "Output pipe did not close after process exit\n" + transcript());
                observe(line);
                if (END.equals(line)) break;
            }
            // On Windows a process-exit notification can precede final kernel handle cleanup.
            // Wait for the actual lock-release condition, without retrying engine operations or
            // requesting any graceful shutdown/close in the child.
            try (FileChannel lockFile = FileChannel.open(directory.resolve("LOCK"), StandardOpenOption.WRITE)) {
                while (true) {
                    FileLock lock = lockFile.tryLock();
                    if (lock != null) {
                        lock.close();
                        break;
                    }
                    assertTrue(System.nanoTime() < deadline, "OS lock was not released after termination");
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            }
        }

        private void observe(String line) {
            transcript.add("CHILD> " + line);
            // Killing the writer could leave a partial final protocol line at pipe EOF.
            // Only complete messages constitute observed issues/acknowledgements.
            if (line.matches("ACK [0-9]+ [0-9]+")) acknowledged = Math.max(acknowledged,
                    Integer.parseInt(line.split(" ")[1]));
            if (line.matches("ISSUED [0-9]+")) issued = Math.max(issued,
                    Integer.parseInt(line.split(" ")[1]));
        }

        int acknowledged() { return acknowledged; }
        int issued() { return issued; }
        String transcript() {
            synchronized (transcript) { return String.join("\n", transcript) + "\n"; }
        }

        @Override public void close() throws Exception {
            if (process.isAlive()) kill();
            // A pipelined writer may still have buffered bytes when TerminateProcess closes
            // the pipe. Discard that expected parent-side cleanup error after confirmed death.
            try { input.close(); }
            catch (java.io.IOException terminatedPipe) {
                if (process.isAlive()) throw terminatedPipe;
            }
        }
    }
}
