package io.durablekv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

/** Repeatable, single-thread synchronous workload runner; deliberately not a microbenchmark framework. */
public final class Benchmark {
    private Benchmark() {}
    @FunctionalInterface private interface Operation { void run(int index) throws IOException; }

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 6)
            throw new IllegalArgumentException("Benchmark DATA_DIRECTORY OUTPUT_DIRECTORY [records=1000] [valueBytes=1024] [seed=424242] [revision=working-tree]");
        Path data = Path.of(args[0]).toAbsolutePath();
        Path output = Path.of(args[1]).toAbsolutePath();
        int records = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        int valueBytes = args.length > 3 ? Integer.parseInt(args[3]) : 1024;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : 424242;
        if (records < 1 || records > 1_000_000 || valueBytes < 0 || valueBytes > 1_048_576)
            throw new IllegalArgumentException("records must be 1..1000000, valueBytes 0..1048576");
        Files.createDirectories(data);
        try (var entries = Files.list(data)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("Benchmark data directory must be empty: " + data);
        }
        Files.createDirectories(output);
        byte[] value = new byte[valueBytes];
        new Random(seed).nextBytes(value);
        Properties metadata = new Properties();
        metadata.setProperty("java.version", System.getProperty("java.version"));
        metadata.setProperty("java.vendor", System.getProperty("java.vendor"));
        metadata.setProperty("java.vm.name", System.getProperty("java.vm.name"));
        metadata.setProperty("heap.max.bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        metadata.setProperty("os.name", System.getProperty("os.name"));
        metadata.setProperty("os.version", System.getProperty("os.version"));
        metadata.setProperty("os.arch", System.getProperty("os.arch"));
        metadata.setProperty("filesystem", Files.getFileStore(data).toString());
        metadata.setProperty("filesystem.type", Files.getFileStore(data).type());
        metadata.setProperty("storage.device", "See platform.txt captured alongside results; Java exposes no portable device model");
        metadata.setProperty("revision", args.length > 5 ? args[5] : "working-tree (uncommitted; see source-sha256.txt)");
        metadata.setProperty("seed", Long.toString(seed));
        metadata.setProperty("records", Integer.toString(records));
        metadata.setProperty("value.bytes", Integer.toString(valueBytes));
        metadata.setProperty("key.format", "k + decimal index; 2.." + (1 + Integer.toString(records - 1).length()) + " UTF-8 bytes");
        metadata.setProperty("thread.count", "1");
        metadata.setProperty("force.policy", "FileChannel.force(true) after every mutation; no buffered comparison");
        metadata.setProperty("warmup", "100 forced writes + 1000 reads in a separate store");
        metadata.setProperty("mixed.operations", "60% reads / 30% puts / 10% deletes, deterministic seed");
        metadata.setProperty("measurement", "System.nanoTime; per-call latency includes workload call and read validation; throughput includes raw CSV writing");
        try (var writer = Files.newBufferedWriter(output.resolve("environment.properties"), StandardCharsets.UTF_8)) {
            metadata.store(writer, "DurableKV benchmark environment");
        }
        try (var warm = DurableKv.open(data.resolve("warmup"))) {
            for (int i = 0; i < 100; i++) warm.put("warm" + i, value);
            for (int i = 0; i < 1000; i++) warm.get("warm" + (i % 100));
        }
        Path storePath = data.resolve("measured");
        try (BufferedWriter raw = Files.newBufferedWriter(output.resolve("latencies.csv"));
             BufferedWriter summary = Files.newBufferedWriter(output.resolve("summary.csv"))) {
            raw.write("workload,index,latency_ns\n");
            summary.write("workload,operations,wall_ns,ops_per_second,p50_ns,p95_ns,p99_ns,log_bytes_before,log_bytes_after\n");
            try (DurableKv store = DurableKv.open(storePath)) {
                measure("write", records, store, raw, summary, i -> store.put("k" + i, value));
                Random reads = new Random(seed + 1);
                measure("read", records * 2, store, raw, summary, i -> {
                    byte[] actual = store.get("k" + reads.nextInt(records)).orElseThrow();
                    if (!Arrays.equals(value, actual)) throw new AssertionError("Read value mismatch");
                });
                Random mixed = new Random(seed + 2);
                measure("mixed", records, store, raw, summary, i -> {
                    String key = "k" + mixed.nextInt(records);
                    int choice = i % 10;
                    if (choice < 6) store.get(key);
                    else if (choice < 9) store.put(key, value);
                    else store.delete(key);
                });
                Random overwrites = new Random(seed + 3);
                measure("overwrite", records, store, raw, summary,
                        i -> store.put("k" + overwrites.nextInt(Math.max(1, records / 10)), value));
                measure("compact", 1, store, raw, summary, i -> store.compact());
            }
            long before = generationBytes(storePath);
            long start = System.nanoTime();
            long sequence;
            try (DurableKv reopened = DurableKv.open(storePath)) {
                sequence = reopened.stats().sequence();
            }
            long elapsed = System.nanoTime() - start;
            raw.write("reopen,0," + elapsed + "\n");
            summary.write("reopen,1," + elapsed + "," + (1e9 / elapsed) + "," + elapsed + "," + elapsed + "," + elapsed + "," + before + "," + generationBytes(storePath) + "\n");
            Files.writeString(output.resolve("result.txt"), "Completed workloads; final sequence=" + sequence + "\n");
        }
        System.out.println("Benchmark complete: " + output);
    }

    private static void measure(String name, int count, DurableKv store, BufferedWriter raw,
                                BufferedWriter summary, Operation operation) throws IOException {
        long before = store.stats().logBytes();
        long[] latencies = new long[count];
        long wallStart = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            operation.run(i);
            latencies[i] = System.nanoTime() - start;
            raw.write(name + "," + i + "," + latencies[i] + "\n");
        }
        long wall = System.nanoTime() - wallStart;
        Arrays.sort(latencies);
        summary.write(name + "," + count + "," + wall + "," + (count * 1e9 / wall) + ","
                + percentile(latencies, .50) + "," + percentile(latencies, .95) + "," + percentile(latencies, .99)
                + "," + before + "," + store.stats().logBytes() + "\n");
        summary.flush();
        raw.flush();
    }

    private static long percentile(long[] sorted, double percentile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)];
    }

    private static long generationBytes(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            long total = 0;
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".log")).toList()) total += Files.size(path);
            return total;
        }
    }
}
