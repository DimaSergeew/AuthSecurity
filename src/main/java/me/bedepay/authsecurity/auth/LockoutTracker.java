package me.bedepay.authsecurity.auth;

import me.bedepay.authsecurity.config.PluginConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-account brute-force lockout that survives reconnects.
 * Keyed by UUID — stable across the lifetime of a registered account.
 *
 * <p>Failure counts reset automatically after a successful login via {@link #reset(UUID)},
 * or once the ban window elapses and the player retries.
 */
public final class LockoutTracker {

    private record Entry(AtomicInteger failures, long lockedUntilMillis) {
        Entry withFailures(int count) { return new Entry(new AtomicInteger(count), lockedUntilMillis); }
    }

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private volatile PluginConfig.LockoutConfig config;

    public LockoutTracker(PluginConfig.LockoutConfig config) {
        this.config = config;
    }

    public void applyConfig(PluginConfig.LockoutConfig config) {
        this.config = config;
    }

    /**
     * @return minutes remaining on an active lock, or {@code 0} if the account is not locked.
     */
    public long remainingLockMinutes(UUID uuid) {
        if (!config.enabled()) return 0;
        Entry entry = entries.get(uuid);
        if (entry == null) return 0;
        long remainingMs = entry.lockedUntilMillis() - System.currentTimeMillis();
        if (remainingMs <= 0) {
            entries.remove(uuid, entry);
            return 0;
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(remainingMs));
    }

    /**
     * Records a failed attempt. Returns {@code true} if this failure triggered a lock.
     */
    public boolean recordFailure(UUID uuid) {
        if (!config.enabled()) return false;
        int max = Math.max(1, config.maxAttempts());
        long banMs = TimeUnit.MINUTES.toMillis(Math.max(1, config.banMinutes()));

        Entry updated = entries.compute(uuid, (key, existing) -> {
            if (existing == null) {
                return new Entry(new AtomicInteger(1), 0L);
            }
            int count = existing.failures().incrementAndGet();
            if (count >= max) {
                return new Entry(new AtomicInteger(0), System.currentTimeMillis() + banMs);
            }
            return existing;
        });
        return updated.lockedUntilMillis() > 0;
    }

    public void reset(UUID uuid) {
        entries.remove(uuid);
    }
}
