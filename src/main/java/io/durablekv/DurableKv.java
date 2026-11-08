package io.durablekv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Single-process, multi-threaded durable append-only key/value store.
 * See docs/storage-format.md for recovery and process-crash durability boundaries. */
public final class DurableKv implements AutoCloseable {
    private enum State { OPEN, FAILED, CLOSED }
    private final Path directory;
    private final Options options;
    private final Io io;
    private final Hooks hooks;
    private final ReentrantReadWriteLock mutex = new ReentrantReadWriteLock(true);
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private FileChannel log;
    private UUID generation;
    private Map<String, Format.Entry> index;
    private long sequence, records, logBytes, liveValueBytes;
    private volatile State state = State.OPEN;

    private DurableKv(Path directory, Options options, Io io, Hooks hooks,
                      FileChannel lockChannel, FileLock processLock) {
        this.directory = directory; this.options = options; this.io = io; this.hooks = hooks;
        this.lockChannel = lockChannel; this.processLock = processLock;
    }

    public static DurableKv open(Path directory) throws IOException { return open(directory, Options.defaults()); }
    public static DurableKv open(Path directory, Options options) throws IOException {
        return openForTesting(directory, options, Io.SYSTEM, Hooks.NONE);
    }
    static DurableKv openForTesting(Path directory, Options options, Io io, Hooks hooks) throws IOException {
        Objects.requireNonNull(directory, "directory"); Objects.requireNonNull(options, "options");
        Objects.requireNonNull(io, "io"); Objects.requireNonNull(hooks, "hooks");
        Path dir = directory.toAbsolutePath().normalize(); Files.createDirectories(dir);
        boolean initiallyEmpty;
        try (var contents = Files.list(dir)) { initiallyEmpty = contents.findAny().isEmpty(); }
        FileChannel lockChannel = FileChannel.open(dir.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        DurableKv store = null;
        try {
            FileLock lock = acquire(lockChannel);
            store = new DurableKv(dir, options, io, hooks, lockChannel, lock);
            if (!Files.exists(dir.resolve("MANIFEST"))) {
                boolean onlyLock;
                try (var contents = Files.list(dir)) {
                    onlyLock = contents.allMatch(p -> p.getFileName().toString().equals("LOCK"));
                }
                if (!initiallyEmpty || !onlyLock)
                    throw new MissingStoreException("Nonempty directory has no authoritative MANIFEST: " + dir);
                store.initialize();
            }
            store.generation = readAuthority(dir, io);
            Path selected = store.generationPath(store.generation);
            if (!Files.isRegularFile(selected)) throw new MissingStoreException("Manifest generation is missing: " + selected);
            store.log = FileChannel.open(selected, StandardOpenOption.READ, StandardOpenOption.WRITE);
            Format.Scan scan = Format.scan(store.log, store.generation, io);
            if (scan.tailBytes() != 0) {
                store.log.truncate(scan.validBytes()); io.force(store.log);
            }
            store.install(scan);
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            try { if (store != null) store.close(); else lockChannel.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Validate authoritative files without initialization, tail repair or cleanup. Needs exclusive ownership. */
    public static Inspection inspect(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory"); Path dir = directory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dir.resolve("MANIFEST")) || !Files.isRegularFile(dir.resolve("LOCK")))
            throw new MissingStoreException("Inspection requires existing MANIFEST and LOCK: " + dir);
        try (FileChannel locks = FileChannel.open(dir.resolve("LOCK"), StandardOpenOption.WRITE);
             FileLock ignored = acquire(locks)) {
            UUID id = readAuthority(dir, Io.SYSTEM); Path path = dir.resolve("gen-" + id + ".log");
            if (!Files.isRegularFile(path)) throw new MissingStoreException("Manifest generation is missing: " + path);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                Format.Scan scan = Format.scan(channel, id, Io.SYSTEM);
                return new Inspection(new Stats(id, scan.sequence(), scan.index().size(), channel.size(),
                        scan.records(), scan.liveValueBytes()), scan.tailBytes());
            }
        }
    }
    private static FileLock acquire(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new StoreLockedException("Store already open in another process");
            return lock;
        } catch (OverlappingFileLockException e) { throw new StoreLockedException("Store already open in this process"); }
    }
    private static UUID readAuthority(Path dir, Io io) throws IOException {
        try (FileChannel manifest = FileChannel.open(dir.resolve("MANIFEST"), StandardOpenOption.READ)) {
            return Format.readManifest(manifest, io);
        } catch (NoSuchFileException e) { throw new MissingStoreException("Missing authoritative MANIFEST: " + dir); }
    }
    private Path generationPath(UUID id) { return directory.resolve("gen-" + id + ".log"); }
    private void initialize() throws IOException {
        UUID id = UUID.randomUUID();
        prepareGeneration(id, 0, List.of(), "init");
        publish(id, "init");
        hooks.hit("init.activated"); hooks.hit("init.cleaned");
    }
    private void install(Format.Scan scan) {
        index = scan.index(); sequence = scan.sequence(); records = scan.records();
        logBytes = scan.validBytes(); liveValueBytes = scan.liveValueBytes();
    }
    private void checkOpen() {
        if (state != State.OPEN) throw new IllegalStateException("Store is " + state.name().toLowerCase(Locale.ROOT)
                + (state == State.FAILED ? "; close and reopen to recover" : ""));
    }
    private StoreException fail(String operation, IOException cause) {
        state = State.FAILED; return new StoreException(operation + " failed; close and reopen. Outcome may be durable", cause);
    }

    public long put(String key, byte[] value) throws IOException {
        mutex.writeLock().lock();
        try {
            checkOpen();
            byte[] encoded = Format.keyBytes(key, options.maxKeyBytes());
            if (value == null || value.length > options.maxValueBytes())
                throw new IllegalArgumentException("Value must be nonnull and at most " + options.maxValueBytes() + " bytes");
            return mutate(key, encoded, value.clone(), (byte) 1);
        } finally { mutex.writeLock().unlock(); }
    }
    public long delete(String key) throws IOException {
        mutex.writeLock().lock();
        try { checkOpen(); return mutate(key, Format.keyBytes(key, options.maxKeyBytes()), new byte[0], (byte) 2); }
        finally { mutex.writeLock().unlock(); }
    }
    private long mutate(String key, byte[] keyBytes, byte[] value, byte type) throws IOException {
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Mutation sequence exhausted");
        long next = sequence + 1;
        ByteBuffer record = Format.record(type, next, keyBytes, value);
        int length = record.remaining();
        try {
            if (hooks != Hooks.NONE) {
                ByteBuffer prefix = record.slice(); prefix.limit(12);
                Format.writeFully(log, prefix, logBytes, io); record.position(12); hooks.hit("append.partial");
                Format.writeFully(log, record, logBytes + 12, io);
            } else Format.writeFully(log, record, logBytes, io);
            hooks.hit("append.complete");
            io.force(log); hooks.hit("append.forced");
            Format.Entry old = index.remove(key);
            if (old != null) liveValueBytes -= old.valueLength();
            if (type == 1) {
                index.put(key, new Format.Entry(logBytes + Format.RECORD_HEADER + keyBytes.length, value.length, next));
                liveValueBytes += value.length;
            }
            sequence = next; records++; logBytes += length;
            hooks.hit("append.indexed");
            return next;
        } catch (IOException e) { throw fail("Mutation", e); }
    }
    public Optional<byte[]> get(String key) throws IOException {
        mutex.readLock().lock();
        try {
            checkOpen(); Format.keyBytes(key, Options.MAX_KEY_BYTES);
            Format.Entry entry = index.get(key);
            if (entry == null) return Optional.empty();
            try { return Optional.of(readValue(entry)); }
            catch (IOException e) { throw fail("Read", e); }
        } finally { mutex.readLock().unlock(); }
    }
    private byte[] readValue(Format.Entry entry) throws IOException {
        ByteBuffer value = ByteBuffer.allocate(entry.valueLength());
        Format.readFully(log, value, entry.valueOffset(), io); return value.array();
    }
    public Stats stats() {
        mutex.readLock().lock();
        try { checkOpen(); return new Stats(generation, sequence, index.size(), logBytes, records, liveValueBytes); }
        finally { mutex.readLock().unlock(); }
    }

    public void compact() throws IOException {
        mutex.writeLock().lock();
        try {
            checkOpen(); UUID id = UUID.randomUUID(); UUID oldId = generation;
            try {
                List<Map.Entry<String, Format.Entry>> entries = new ArrayList<>(index.entrySet());
                entries.sort(Comparator.comparingLong(e -> e.getValue().sequence()));
                prepareGeneration(id, sequence, entries, "compact");
                publish(id, "compact");
                // The manifest now owns authority. Any activation error fails this handle.
                FileChannel replacement = FileChannel.open(generationPath(id), StandardOpenOption.READ, StandardOpenOption.WRITE);
                try {
                    Format.Scan scan = Format.scan(replacement, id, io);
                    FileChannel old = log; log = replacement; replacement = null;
                    generation = id; install(scan); old.close();
                } finally { if (replacement != null) replacement.close(); }
                hooks.hit("compact.activated");
                Files.deleteIfExists(generationPath(oldId));
                hooks.hit("compact.cleaned");
            } catch (UnsupportedCompactionException e) {
                throw e;
            } catch (IOException e) { throw fail("Compaction", e); }
        } finally { mutex.writeLock().unlock(); }
    }
    private void prepareGeneration(UUID id, long highWater, List<Map.Entry<String, Format.Entry>> entries, String phase)
            throws IOException {
        try (FileChannel out = FileChannel.open(generationPath(id), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            hooks.hit(phase + ".generation.created");
            Format.writeFully(out, Format.header(id, highWater, entries.size()), 0, io);
            long position = Format.HEADER_SIZE;
            for (var entry : entries) {
                byte[] key = Format.keyBytes(entry.getKey(), Options.MAX_KEY_BYTES);
                ByteBuffer record = Format.record((byte) 1, entry.getValue().sequence(), key, readValue(entry.getValue()));
                int length = record.remaining(); Format.writeFully(out, record, position, io); position += length;
            }
            hooks.hit(phase + ".generation.written");
            Format.Scan scan = Format.scan(out, id, io);
            if (scan.tailBytes() != 0 || scan.sequence() != highWater || scan.index().size() != entries.size())
                throw new CorruptStoreException("Prepared generation validation failed");
            hooks.hit(phase + ".generation.validated");
            io.force(out); hooks.hit(phase + ".generation.forced");
        }
    }
    private void publish(UUID id, String phase) throws IOException {
        Path temporary = directory.resolve("manifest-" + id + ".tmp");
        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Format.writeFully(out, Format.manifest(id), 0, io); hooks.hit(phase + ".manifest.written");
            io.force(out); hooks.hit(phase + ".manifest.forced");
        }
        hooks.hit(phase + ".before.publish");
        try { io.atomicMove(temporary, directory.resolve("MANIFEST")); }
        catch (AtomicMoveNotSupportedException e) {
            throw new UnsupportedCompactionException("Atomic manifest publication unsupported; prior authority is unchanged", e);
        }
        hooks.hit(phase + ".published");
    }

    @Override public void close() throws IOException {
        mutex.writeLock().lock();
        try {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
            IOException error = null;
            for (AutoCloseable resource : new AutoCloseable[] { log, processLock, lockChannel }) {
                if (resource == null) continue;
                try { resource.close(); }
                catch (Exception e) {
                    IOException ioe = e instanceof IOException x ? x : new IOException(e);
                    if (error == null) error = ioe; else error.addSuppressed(ioe);
                }
            }
            if (index != null) index.clear();
            if (error != null) throw error;
        } finally { mutex.writeLock().unlock(); }
    }
}
