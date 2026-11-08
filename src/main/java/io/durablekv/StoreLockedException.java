package io.durablekv;

public final class StoreLockedException extends StoreException {
    public StoreLockedException(String message) { super(message); }
}
