package io.durablekv;

/** Per-handle write limits; format/recovery limits are always the version 1 maxima. */
public record Options(int maxKeyBytes, int maxValueBytes) {
    public static final int MAX_KEY_BYTES = 1024;
    public static final int MAX_VALUE_BYTES = 1024 * 1024;
    public Options {
        if (maxKeyBytes < 1 || maxKeyBytes > MAX_KEY_BYTES || maxValueBytes < 0 || maxValueBytes > MAX_VALUE_BYTES)
            throw new IllegalArgumentException("Limits must fit format bounds: key 1..1024, value 0..1048576");
    }
    public static Options defaults() { return new Options(MAX_KEY_BYTES, MAX_VALUE_BYTES); }
}
