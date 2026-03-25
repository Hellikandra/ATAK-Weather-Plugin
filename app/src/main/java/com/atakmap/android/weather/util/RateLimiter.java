package com.atakmap.android.weather.util;

/**
 * Simple sliding-window rate limiter for API calls.
 *
 * <p>Maintains a queue of request timestamps and enforces a maximum number of
 * requests within a rolling time window. Thread-safe via synchronized access
 * to the internal timestamp queue.</p>
 *
 * <p>Usage example (RainViewer 100 req/min limit, with 10-req headroom):</p>
 * <pre>
 *   RateLimiter limiter = new RateLimiter(90, 60_000);
 *   if (!limiter.tryAcquire()) {
 *       Log.w(TAG, "Rate limit approaching — skipping request");
 *       return;
 *   }
 * </pre>
 */
public class RateLimiter {
    private final int maxRequests;
    private final long windowMs;
    private final java.util.LinkedList<Long> timestamps = new java.util.LinkedList<>();

    /**
     * Create a new rate limiter.
     *
     * @param maxRequests maximum number of requests allowed within the window
     * @param windowMs   sliding window duration in milliseconds
     */
    public RateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    /**
     * Try to acquire a permit. Returns {@code true} if within the rate limit,
     * {@code false} if the limit has been reached for the current window.
     */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        // Remove timestamps outside the window
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= maxRequests) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }

    /**
     * Block until a permit is available, up to {@code maxWaitMs} milliseconds.
     *
     * @param maxWaitMs maximum time to wait in milliseconds
     * @return {@code true} if a permit was acquired, {@code false} if timed out
     *         or interrupted
     */
    public boolean acquireOrWait(long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (tryAcquire()) return true;
            try { Thread.sleep(100); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    /**
     * Returns the number of requests remaining in the current window.
     *
     * @return remaining permits (0 if limit reached)
     */
    public synchronized int remaining() {
        long now = System.currentTimeMillis();
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
            timestamps.pollFirst();
        }
        return Math.max(0, maxRequests - timestamps.size());
    }
}
