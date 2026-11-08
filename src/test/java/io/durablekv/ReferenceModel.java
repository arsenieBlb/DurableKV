package io.durablekv;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Independent specification used by state-machine and linearizability checks. */
final class ReferenceModel {
    private final Map<String, byte[]> values;
    private long sequence;

    ReferenceModel() { values = new HashMap<>(); }

    private ReferenceModel(ReferenceModel original) {
        values = new HashMap<>();
        original.values.forEach((key, value) -> values.put(key, value.clone()));
        sequence = original.sequence;
    }

    ReferenceModel copy() { return new ReferenceModel(this); }

    long put(String key, byte[] value) {
        values.put(key, value.clone());
        return ++sequence;
    }

    long delete(String key) {
        values.remove(key);
        return ++sequence;
    }

    Optional<byte[]> get(String key) {
        byte[] value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    long sequence() { return sequence; }
    int size() { return values.size(); }

    static boolean equal(Optional<byte[]> left, Optional<byte[]> right) {
        return left.isEmpty() ? right.isEmpty()
                : right.isPresent() && Arrays.equals(left.orElseThrow(), right.orElseThrow());
    }
}
