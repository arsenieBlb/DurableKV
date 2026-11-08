package io.durablekv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** A deliberately killable JVM. There is no shutdown hook or recovery in a finally block. */
public final class CrashProcess {
    private static final BufferedReader INPUT = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private static final PrintWriter OUTPUT = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    private static String pauseAt;
    private static int occurrence;
    private static int seen;

    private CrashProcess() { }

    static void checkpoint(String point) throws java.io.IOException {
        if (point.equals(pauseAt) && ++seen == occurrence) {
            OUTPUT.println("PAUSE " + point);
            if (!"CONTINUE".equals(INPUT.readLine())) {
                throw new java.io.IOException("Parent disconnected while paused at " + point);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        pauseAt = args[1];
        occurrence = Integer.parseInt(args[2]);
        // This package-private entry point is available to tests only; ordinary open has no hooks.
        DurableKv store = DurableKv.openForTesting(Path.of(args[0]), Options.defaults(),
                new Io() { }, CrashProcess::checkpoint);
        OUTPUT.println("READY");
        String line;
        while ((line = INPUT.readLine()) != null) {
            String[] fields = line.split(" ", -1);
            String id = fields[0];
            OUTPUT.println("ISSUED " + id);
            switch (fields[1]) {
                case "PUT" -> {
                    long sequence = store.put(fields[2], Base64.getDecoder().decode(fields[3]));
                    checkpoint("response.before");
                    OUTPUT.println("ACK " + id + " " + sequence);
                    checkpoint("response.acknowledged");
                }
                case "DELETE" -> {
                    long sequence = store.delete(fields[2]);
                    checkpoint("response.before");
                    OUTPUT.println("ACK " + id + " " + sequence);
                    checkpoint("response.acknowledged");
                }
                case "COMPACT" -> {
                    store.compact();
                    OUTPUT.println("ACK " + id + " 0");
                }
                case "CLOSE" -> {
                    store.close();
                    OUTPUT.println("CLOSED");
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown command " + line);
            }
        }
        // EOF and external termination intentionally do not call close().
    }
}
