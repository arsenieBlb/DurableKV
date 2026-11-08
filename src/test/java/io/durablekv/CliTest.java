package io.durablekv;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    private record Result(int code, String output, String error) {}
    private static Result run(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            return new Result(Cli.run(args, out, err), output.toString(StandardCharsets.UTF_8),
                    error.toString(StandardCharsets.UTF_8));
        }
    }

    @Test void commandsExerciseLibraryIncludingBinaryValuesAndMissingResult() throws Exception {
        String path = EngineTest.directory("cli").toString();
        Result put = run("put", path, "key", "--base64", "AP8=");
        assertEquals(0, put.code(), put.error());
        assertEquals("1", put.output().trim());
        assertEquals("AP8=", run("get", path, "key").output().trim());
        assertEquals(0, run("stats", path).code());
        assertEquals(0, run("compact", path).code());
        assertEquals(0, run("inspect", path).code());
        Result delete = run("delete", path, "key");
        assertEquals(0, delete.code(), delete.error());
        assertEquals("2", delete.output().trim());
        Result missing = run("get", path, "key");
        assertEquals(1, missing.code());
        assertEquals("MISSING", missing.output().trim());
        Result text = run("put", path, "unicode", "héllo🙂");
        assertEquals(0, text.code(), text.error());
        assertEquals("héllo🙂", run("get", path, "unicode", "--utf8").output().trim());
    }

    @Test void inspectionNeverRepairsAndExplicitRecoveryTrimsOnlyRecognizedIncompleteTail() throws Exception {
        Path path = EngineTest.directory("cli-recover");
        assertEquals(0, run("put", path.toString(), "key", "value").code());
        Path generation = FramingTest.generation(path);
        long validSize = Files.size(generation);
        Files.write(generation, new byte[]{0, 1, 2}, StandardOpenOption.APPEND);
        Result inspect = run("inspect", path.toString());
        assertEquals(3, inspect.code(), inspect.error());
        assertEquals(validSize + 3, Files.size(generation));
        assertEquals(0, run("recover", path.toString()).code());
        assertEquals(validSize, Files.size(generation));
        byte[] corrupted = Files.readAllBytes(generation);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(generation, corrupted);
        assertEquals(2, run("recover", path.toString()).code());
        assertArrayEquals(corrupted, Files.readAllBytes(generation));
    }

    @Test void errorsDoNotPrintSuccessAndDoNotLeakHandles() throws Exception {
        String path = EngineTest.directory("cli-errors").toString();
        Result badBase64 = run("put", path, "key", "--base64", "invalid!");
        assertEquals(2, badBase64.code());
        assertTrue(badBase64.output().isEmpty());
        assertFalse(badBase64.error().isBlank());
        assertEquals(2, run("unknown", path).code());
        assertEquals(2, run("put", path, "key").code());
        assertEquals(0, run("--help").code());
        assertEquals(0, run("put", path, "key", "value").code());
        try (DurableKv store = DurableKv.open(Path.of(path))) {
            Result locked = run("put", path, "key", "replacement");
            assertEquals(2, locked.code());
            assertTrue(locked.output().isEmpty());
        }
        assertEquals(0, run("get", path, "key").code());
    }
}
