package org.aadi.triptourworld.security;

import java.util.concurrent.ConcurrentHashMap;

/** Fixed-window, in-memory rate limiter (per key). Good enough for a single-instance PoC. */
public final class RateLimiter {
    private final int limit;
    private final long windowMs;
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public RateLimiter(int limitPerWindow, long windowMs) {
        this.limit = Math.max(1, limitPerWindow);
        this.windowMs = windowMs;
    }

    public boolean allow(String key, long nowMs) {
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> nowMs - e.getValue()[0] > windowMs);
        }
        boolean[] allowed = new boolean[1];
        windows.compute(key, (k, w) -> {
            if (w == null || nowMs - w[0] >= windowMs) {
                allowed[0] = true;
                return new long[] {nowMs, 1};
            }
            if (w[1] < limit) {
                w[1]++;
                allowed[0] = true;
            }
            return w;
        });
        return allowed[0];
    }
}
