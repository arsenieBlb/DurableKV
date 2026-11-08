package io.durablekv;

public final class CorruptStoreException extends StoreException {
    public CorruptStoreException(String message) { super(message); }
    public CorruptStoreException(String message, Throwable cause) { super(message, cause); }
}
