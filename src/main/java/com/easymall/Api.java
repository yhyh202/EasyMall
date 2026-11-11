package com.easymall;

import java.time.Instant;
import java.util.List;

public record Api<T>(String code, String message, T data, Instant timestamp) {
    public static <T> Api<T> ok(T data) { return new Api<>("OK", "success", data, Instant.now()); }
    public static Api<Void> error(String code, String message) { return new Api<>(code, message, null, Instant.now()); }
    public record Page<T>(List<T> items, long total, int page, int size) {}
}
