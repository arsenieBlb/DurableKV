package io.durablekv;

import java.io.IOException;

/** Tests must explicitly inject hooks; there is no system-property/environment back door. */
@FunctionalInterface
interface Hooks {
    Hooks NONE = name -> {};
    void hit(String name) throws IOException;
}
