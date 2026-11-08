package io.durablekv;

import java.io.IOException;

/** Base checked storage failure. A failed mutation may still survive recovery. */
public class StoreException extends IOException {
    public StoreException(String message) { super(message); }
    public StoreException(String message, Throwable cause) { super(message, cause); }
}
