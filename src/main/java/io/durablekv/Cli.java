package io.durablekv;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Small local command-line adapter. Values are UTF-8 on input and Base64 on output by default. */
public final class Cli {
    private Cli() {}

    public static void main(String[] args) {
        int result = run(args, System.out, System.err);
        if (result != 0) System.exit(result);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("help"))) {
                usage(out);
                return 0;
            }
            if (args.length < 2) throw new IllegalArgumentException("Expected a command and store directory; use --help");
            String command = args[0];
            Path directory = Path.of(args[1]);
            switch (command) {
                case "put" -> {
                    if (args.length != 4 && !(args.length == 5 && args[3].equals("--base64")))
                        throw new IllegalArgumentException("put DIRECTORY KEY VALUE | put DIRECTORY KEY --base64 VALUE");
                    // Bound the encoded argument before decoding or allocating its bytes.
                    String input = args[args.length - 1];
                    boolean binary = args.length == 5;
                    int maxChars = binary ? 4 * ((1_048_576 + 2) / 3) : 1_048_576;
                    if (input.length() > maxChars) throw new IllegalArgumentException("Value exceeds 1 MiB limit");
                    byte[] value = binary ? Base64.getDecoder().decode(input) : boundedUtf8(input);
                    try (DurableKv store = DurableKv.open(directory)) { out.println(store.put(args[2], value)); }
                }
                case "get" -> {
                    if (args.length != 3 && !(args.length == 4 && args[3].equals("--utf8")))
                        throw new IllegalArgumentException("get DIRECTORY KEY [--utf8]");
                    try (DurableKv store = DurableKv.open(directory)) {
                        var value = store.get(args[2]);
                        if (value.isEmpty()) { out.println("MISSING"); return 1; }
                        out.println(args.length == 4 ? new String(value.get(), StandardCharsets.UTF_8)
                                : Base64.getEncoder().encodeToString(value.get()));
                    }
                }
                case "delete" -> {
                    requireCount(args, 3);
                    try (DurableKv store = DurableKv.open(directory)) { out.println(store.delete(args[2])); }
                }
                case "stats", "compact" -> {
                    requireCount(args, 2);
                    try (DurableKv store = DurableKv.open(directory)) {
                        if (command.equals("compact")) store.compact();
                        out.println(store.stats());
                    }
                }
                case "inspect" -> {
                    requireCount(args, 2);
                    var inspection = DurableKv.inspect(directory);
                    out.println(inspection);
                    if (inspection.incompleteTailBytes() > 0) {
                        err.println("Recognised incomplete tail; inspect did not change files. Use recover to trim it.");
                        return 3;
                    }
                }
                case "recover" -> {
                    requireCount(args, 2);
                    if (!Files.exists(directory.resolve("MANIFEST")))
                        throw new IllegalArgumentException("recover requires an existing MANIFEST");
                    try (DurableKv store = DurableKv.open(directory)) { out.println(store.stats()); }
                }
                default -> throw new IllegalArgumentException("Unknown command: " + command + "; use --help");
            }
            return 0;
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            err.println("durablekv: " + failure.getMessage());
            return 2;
        }
    }

    private static void requireCount(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("Unexpected arguments; use --help");
    }

    private static byte[] boundedUtf8(String text) throws IOException {
        var encoder = StandardCharsets.UTF_8.newEncoder();
        ByteBuffer bytes = ByteBuffer.allocate((int) Math.min(1_048_576L, text.length() * 3L));
        var result = encoder.encode(CharBuffer.wrap(text), bytes, true);
        if (result.isOverflow()) throw new IllegalArgumentException("Value exceeds 1 MiB UTF-8 limit");
        if (result.isError()) result.throwException();
        result = encoder.flush(bytes);
        if (result.isError()) result.throwException();
        byte[] value = new byte[bytes.position()];
        bytes.flip().get(value);
        return value;
    }

    private static void usage(PrintStream out) {
        out.println("durablekv <command> <directory> [arguments]");
        out.println("  put DIRECTORY KEY VALUE              UTF-8 value");
        out.println("  put DIRECTORY KEY --base64 VALUE     Binary value");
        out.println("  get DIRECTORY KEY [--utf8]           Base64 output by default; MISSING exits 1");
        out.println("  delete DIRECTORY KEY                 Durable tombstone, prints sequence");
        out.println("  stats DIRECTORY                      Current counters and sizes");
        out.println("  compact DIRECTORY                    Publish compacted generation");
        out.println("  inspect DIRECTORY                    Validate without repair; incomplete tail exits 3");
        out.println("  recover DIRECTORY                    Open existing store and trim only incomplete tail");
        out.println("  Other errors exit 2. Data commands open/recover the store; put creates an empty store.");
    }
}
