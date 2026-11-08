package io.durablekv;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.CRC32C;

final class Format {
    static final int HEADER_SIZE = 56, RECORD_HEADER = 28, MANIFEST_SIZE = 40;
    private static final byte[] LOG_MAGIC = "DKVLOG01".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAN_MAGIC = "DKVMAN01".getBytes(StandardCharsets.US_ASCII);
    record Entry(long valueOffset, int valueLength, long sequence) {}
    record Scan(Map<String, Entry> index, long sequence, long records, long validBytes,
                long tailBytes, long liveValueBytes) {}
    private Format() {}

    static byte[] keyBytes(String key, int limit) {
        if (key == null || key.isEmpty() || key.length() > limit)
            throw new IllegalArgumentException("Key must be nonempty and at most " + limit + " UTF-8 bytes");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(key));
            if (bytes.remaining() > limit) throw new IllegalArgumentException("Encoded key exceeds " + limit + " bytes");
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Key contains invalid Unicode", e); }
    }
    static String decodeKey(byte[] bytes) throws CorruptStoreException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new CorruptStoreException("Invalid UTF-8 key", e); }
    }
    static int checksum(byte[] bytes, int length) {
        CRC32C crc = new CRC32C(); crc.update(bytes, 0, length); return (int) crc.getValue();
    }
    static ByteBuffer header(UUID generation, long highWater, long count) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE);
        b.put(LOG_MAGIC).putInt(1).putInt(HEADER_SIZE).putLong(generation.getMostSignificantBits())
                .putLong(generation.getLeastSignificantBits()).putLong(highWater).putLong(count);
        b.putInt(checksum(b.array(), 48)).putInt(0).flip(); return b;
    }
    static ByteBuffer manifest(UUID generation) {
        ByteBuffer b = ByteBuffer.allocate(MANIFEST_SIZE);
        b.put(MAN_MAGIC).putInt(1).putInt(0).putLong(generation.getMostSignificantBits())
                .putLong(generation.getLeastSignificantBits());
        b.putInt(checksum(b.array(), 32)).putInt(0).flip(); return b;
    }
    static UUID readManifest(FileChannel ch, Io io) throws IOException {
        if (ch.size() != MANIFEST_SIZE) throw corrupt("Invalid manifest length");
        ByteBuffer b = ByteBuffer.allocate(MANIFEST_SIZE); readFully(ch, b, 0, io); b.flip();
        byte[] magic = new byte[8]; b.get(magic);
        if (!Arrays.equals(magic, MAN_MAGIC) || b.getInt() != 1 || b.getInt() != 0)
            throw corrupt("Invalid or unsupported manifest version/magic");
        UUID id = new UUID(b.getLong(), b.getLong());
        if (b.getInt() != checksum(b.array(), 32) || b.getInt() != 0) throw corrupt("Manifest checksum/reserved bytes");
        return id;
    }
    static ByteBuffer record(byte type, long sequence, byte[] key, byte[] value) {
        ByteBuffer b = ByteBuffer.allocate(RECORD_HEADER + key.length + value.length + 4);
        b.putInt(b.capacity()).put((byte) 1).put(type).putShort((short) 0).putLong(sequence)
                .putInt(key.length).putInt(value.length);
        b.putInt(checksum(b.array(), 24)).put(key).put(value);
        b.putInt(checksum(b.array(), b.capacity() - 4)).flip(); return b;
    }
    static Scan scan(FileChannel ch, UUID generation, Io io) throws IOException {
        long size = ch.size();
        if (size < HEADER_SIZE) throw corrupt("Incomplete generation header");
        ByteBuffer h = ByteBuffer.allocate(HEADER_SIZE); readFully(ch, h, 0, io); h.flip();
        byte[] magic = new byte[8]; h.get(magic);
        if (!Arrays.equals(magic, LOG_MAGIC) || h.getInt() != 1 || h.getInt() != HEADER_SIZE)
            throw corrupt("Invalid or unsupported generation version/magic");
        if (!new UUID(h.getLong(), h.getLong()).equals(generation)) throw corrupt("Generation identity mismatch");
        long base = h.getLong(), count = h.getLong();
        if (base < 0 || count < 0 || count > base || count > (size - HEADER_SIZE) / 33
                || h.getInt() != checksum(h.array(), 48) || h.getInt() != 0)
            throw corrupt("Invalid generation header/checksum or incomplete snapshot");
        Map<String, Entry> index = new HashMap<>(); long offset = HEADER_SIZE, records = 0, previous = 0, seq = base;
        while (offset < size) {
            boolean snapshot = records < count;
            if (size - offset < RECORD_HEADER) {
                if (snapshot) throw corrupt("Incomplete snapshot header");
                break;
            }
            ByteBuffer head = ByteBuffer.allocate(RECORD_HEADER); readFully(ch, head, offset, io); head.flip();
            if (head.getInt(24) != checksum(head.array(), 24))
                throw corrupt("Record header checksum failure at " + offset);
            int length = head.getInt(); byte version = head.get(), type = head.get(); short reserved = head.getShort();
            long sequence = head.getLong(); int keyLen = head.getInt(), valLen = head.getInt();
            if (version != 1 || (type != 1 && type != 2) || reserved != 0 || keyLen < 1
                    || keyLen > Options.MAX_KEY_BYTES || valLen < 0 || valLen > Options.MAX_VALUE_BYTES
                    || (type == 2 && valLen != 0) || length != RECORD_HEADER + keyLen + valLen + 4
                    || sequence <= 0 || (snapshot ? sequence <= previous || sequence > base || type != 1 : sequence <= seq))
                throw corrupt("Invalid record header/version/length/sequence at " + offset);
            if (size - offset < length) {
                if (snapshot) throw corrupt("Incomplete snapshot record at " + offset);
                break;
            }
            ByteBuffer full = ByteBuffer.allocate(length); readFully(ch, full, offset, io);
            if (ByteBuffer.wrap(full.array()).getInt(length - 4) != checksum(full.array(), length - 4))
                throw corrupt("Record checksum failure at " + offset);
            String key = decodeKey(Arrays.copyOfRange(full.array(), RECORD_HEADER, RECORD_HEADER + keyLen));
            if (snapshot && index.containsKey(key)) throw corrupt("Duplicate snapshot key at " + offset);
            if (type == 1) index.put(key, new Entry(offset + RECORD_HEADER + keyLen, valLen, sequence));
            else index.remove(key);
            if (snapshot) previous = sequence; else seq = sequence;
            records++; offset += length;
        }
        if (records < count) throw corrupt("Incomplete snapshot");
        long live = 0; for (Entry entry : index.values()) live += entry.valueLength();
        return new Scan(index, seq, records, offset, size - offset, live);
    }
    static void readFully(FileChannel ch, ByteBuffer b, long position, Io io) throws IOException {
        int stalls = 0;
        while (b.hasRemaining()) {
            int n = io.read(ch, b, position);
            if (n < 0) throw corrupt("Unexpected end of file at " + position);
            if (n == 0) { if (++stalls > 100) throw new IOException("Read made no progress"); }
            else { position += n; stalls = 0; }
        }
    }
    static void writeFully(FileChannel ch, ByteBuffer b, long position, Io io) throws IOException {
        int stalls = 0;
        while (b.hasRemaining()) {
            int n = io.write(ch, b, position);
            if (n < 0) throw new IOException("Negative write result");
            if (n == 0) { if (++stalls > 100) throw new IOException("Write made no progress"); }
            else { position += n; stalls = 0; }
        }
    }
    private static CorruptStoreException corrupt(String message) { return new CorruptStoreException(message); }
}
