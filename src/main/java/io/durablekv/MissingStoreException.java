package io.durablekv;

public final class MissingStoreException extends StoreException {
    public MissingStoreException(String message) { super(message); }
}
