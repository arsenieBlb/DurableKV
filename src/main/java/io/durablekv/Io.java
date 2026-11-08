package io.durablekv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Package-private fault injection seam. Public opens always use SYSTEM. */
interface Io {
    Io SYSTEM = new Io() {};
    default int read(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        return channel.read(buffer, position);
    }
    default int write(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        return channel.write(buffer, position);
    }
    default void force(FileChannel channel) throws IOException { channel.force(true); }
    default void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
