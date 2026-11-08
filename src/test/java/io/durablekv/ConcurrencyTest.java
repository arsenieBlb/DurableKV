package io.durablekv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ConcurrencyTest {
    private record Operation(int type, String key, byte[] value) {}
    private record Event(Operation operation, long invocation, long response, Object result) {}

    @Test void smallConcurrentHistoriesAdmitSequentialExplanationRespectingRealTime() throws Exception {
        for (long seed = 0; seed < 24; seed++) {
            try (DurableKv store = DurableKv.open(EngineTest.directory("history-" + seed));
                 ExecutorService threads = Executors.newFixedThreadPool(3)) {
                Random random = new Random(seed);
                List<List<Operation>> programs = new ArrayList<>();
                for (int thread = 0; thread < 3; thread++) {
                    List<Operation> program = new ArrayList<>();
                    for (int step = 0; step < 3; step++) {
                        program.add(new Operation(random.nextInt(3), "key-" + random.nextInt(2),
                                new byte[]{(byte) (thread * 3 + step + 1)}));
                    }
                    programs.add(program);
                }
                AtomicLong clock = new AtomicLong();
                CyclicBarrier start = new CyclicBarrier(3);
                List<Future<List<Event>>> futures = new ArrayList<>();
                for (List<Operation> program : programs) {
                    futures.add(threads.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        List<Event> events = new ArrayList<>();
                        for (Operation operation : program) {
                            long invocation = clock.incrementAndGet();
                            Object result = switch (operation.type()) {
                                case 0 -> store.put(operation.key(), operation.value());
                                case 1 -> store.delete(operation.key());
                                default -> store.get(operation.key());
                            };
                            events.add(new Event(operation, invocation, clock.incrementAndGet(), result));
                        }
                        return events;
                    }));
                }
                List<Event> history = new ArrayList<>();
                for (Future<List<Event>> future : futures) history.addAll(future.get(10, TimeUnit.SECONDS));
                assertTrue(linearizable(history, new boolean[history.size()], new ReferenceModel(), 0),
                        "No legal real-time-respecting order for seed=" + seed + ": " + history);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean linearizable(List<Event> history, boolean[] used, ReferenceModel model, int depth) {
        if (depth == history.size()) return true;
        for (int candidate = 0; candidate < history.size(); candidate++) {
            if (used[candidate]) continue;
            Event event = history.get(candidate);
            boolean ready = true;
            for (int previous = 0; previous < history.size(); previous++) {
                if (!used[previous] && history.get(previous).response() < event.invocation()) {
                    ready = false;
                    break;
                }
            }
            if (!ready) continue;
            ReferenceModel next = model.copy();
            Operation operation = event.operation();
            boolean matches = switch (operation.type()) {
                case 0 -> event.result().equals(next.put(operation.key(), operation.value()));
                case 1 -> event.result().equals(next.delete(operation.key()));
                default -> ReferenceModel.equal((Optional<byte[]>) event.result(), next.get(operation.key()));
            };
            if (!matches) continue;
            used[candidate] = true;
            if (linearizable(history, used, next, depth + 1)) return true;
            used[candidate] = false;
        }
        return false;
    }

    @Test void closeRacingMutationHasOneOfTheTwoLegalOutcomesAndReleasesOwnership() throws Exception {
        for (int trial = 0; trial < 12; trial++) {
            var path = EngineTest.directory("close-race");
            DurableKv store = DurableKv.open(path);
            try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Boolean> put = threads.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        assertEquals(1, store.put("key", new byte[]{1}));
                        return true;
                    } catch (IllegalStateException closed) {
                        return false;
                    }
                });
                Future<?> close = threads.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    store.close();
                    return null;
                });
                boolean successful = put.get(10, TimeUnit.SECONDS);
                close.get(10, TimeUnit.SECONDS);
                store.close();
                try (DurableKv reopened = DurableKv.open(path)) {
                    assertEquals(successful, reopened.get("key").isPresent());
                }
            } finally { store.close(); }
        }
    }

    @Test void compactionRacingReadersAndWritersPreservesReturnedVersions() throws Exception {
        try (DurableKv store = DurableKv.open(EngineTest.directory("compact-concurrent"));
             ExecutorService threads = Executors.newFixedThreadPool(3)) {
            CyclicBarrier start = new CyclicBarrier(3);
            Future<?> writer = threads.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                for (int sequence = 1; sequence <= 40; sequence++) {
                    assertEquals(sequence, store.put("key", new byte[]{(byte) sequence}));
                }
                return null;
            });
            Future<?> compactor = threads.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                for (int pass = 0; pass < 6; pass++) store.compact();
                return null;
            });
            Future<?> reader = threads.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                int seen = 0;
                for (int pass = 0; pass < 100; pass++) {
                    Optional<byte[]> value = store.get("key");
                    if (value.isPresent()) {
                        assertEquals(1, value.orElseThrow().length);
                        int current = Byte.toUnsignedInt(value.orElseThrow()[0]);
                        assertTrue(current >= seen, "Read versions moved backwards");
                        seen = current;
                    } else {
                        assertEquals(0, seen);
                    }
                }
                return null;
            });
            writer.get(15, TimeUnit.SECONDS);
            compactor.get(15, TimeUnit.SECONDS);
            reader.get(15, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{40}, store.get("key").orElseThrow());
        }
    }
}
