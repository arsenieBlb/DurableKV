package io.durablekv;

public final class UnsupportedCompactionException extends StoreException {
    public UnsupportedCompactionException(String message, Throwable cause) { super(message, cause); }
}
