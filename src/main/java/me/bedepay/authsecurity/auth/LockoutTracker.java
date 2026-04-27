package me.bedepay.authsecurity.auth;

import me.bedepay.authsecurity.config.PluginConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-IP brute-force lockout that survives reconnects within a server session.
 * Keyed by IP address so an attacker cannot DoS a specific account by failing
 * its password — only their own source IP gets blocked.
 *
 * <p>Failure counts reset once the ban window elapses and the address retries.
 */
public final class LockoutTracker {

    private record Entry(int failures, long lockedUntilMillis) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile PluginConfig.LockoutConfig config;

    public LockoutTracker(PluginConfig.LockoutConfig config) {
        this.config = config;
    }

    public void applyConfig(PluginConfig.LockoutConfig config) {
        this.config = config;
    }

    /**
     * @return minutes remaining on an active lock, or {@code 0} if the IP is not locked.
     */
    public long remainingLockMinutes(String ip) {
        if (!config.enabled()) return 0;
        Entry entry = entries.get(ip);
        if (entry == null) return 0;
        long remainingMs = entry.lockedUntilMillis() - System.currentTimeMillis();
        if (remainingMs <= 0) {
            entries.remove(ip, entry);
            return 0;
        }
        return Math.max(1, TimeUnit.MILLISECONDS.toMinutes(remainingMs));
    }

    /**
     * Records a failed attempt. Returns {@code true} if this failure triggered a lock.
     */
    public boolean recordFailure(String ip) {
        if (!config.enabled()) return false;
        int max = Math.max(1, config.maxAttempts());
        long banMs = TimeUnit.MINUTES.toMillis(Math.max(1, config.banMinutes()));

        long now = System.currentTimeMillis();
        Entry updated = entries.compute(ip, (key, existing) -> {
            if (existing != null && existing.lockedUntilMillis() > now) {
                return existing;
            }

            int count = existing == null || existing.lockedUntilMillis() > 0
                    ? 1
                    : existing.failures() + 1;
            if (count >= max) {
                return new Entry(0, now + banMs);
            }
            return new Entry(count, 0L);
        });
        return updated.lockedUntilMillis() > 0;
    }

}
