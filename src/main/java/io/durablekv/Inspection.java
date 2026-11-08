package io.durablekv;

/** A read-only scan; incompleteTailBytes can be trimmed by an explicit normal open/recovery. */
public record Inspection(Stats stats, long incompleteTailBytes) {}
