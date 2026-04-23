package me.bedepay.authsecurity.ip;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts distinct player UUIDs currently connected from the same IP address.
 *
 * <p>The config-phase gate calls {@link #tryAcquire(String, UUID, int)} before authenticating.
 * Slots are released when the player disconnects or when the auth attempt fails.
 */
public final class ConnectionTracker {

    private final ConcurrentHashMap<String, Set<UUID>> byIp = new ConcurrentHashMap<>();

    public boolean tryAcquire(String ip, UUID uuid, int limit) {
        if (ip == null) return true;
        final boolean[] accepted = {false};
        byIp.compute(ip, (key, existing) -> {
            Set<UUID> set = existing != null ? existing : Collections.synchronizedSet(new HashSet<>());
            synchronized (set) {
                if (set.contains(uuid)) { accepted[0] = true; return set; }
                if (set.size() >= limit) { accepted[0] = false; return set; }
                set.add(uuid);
                accepted[0] = true;
                return set;
            }
        });
        return accepted[0];
    }

    public void release(String ip, UUID uuid) {
        if (ip == null) return;
        byIp.computeIfPresent(ip, (key, set) -> {
            synchronized (set) {
                set.remove(uuid);
                return set.isEmpty() ? null : set;
            }
        });
    }

    public int countFor(String ip) {
        if (ip == null) return 0;
        Set<UUID> set = byIp.get(ip);
        if (set == null) return 0;
        synchronized (set) { return set.size(); }
    }
}
