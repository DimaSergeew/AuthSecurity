package me.bedepay.authsecurity.captcha;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.bedepay.authsecurity.config.PluginConfig;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks consecutive Cloudflare-rejected /verify attempts per source IP and
 * temporarily blocks IPs that exceed the configured threshold.
 *
 * Important: only real "captcha was wrong" rejections from Cloudflare are
 * counted — network errors, DB errors, and malformed-request rejections are
 * not, so legitimate players on flaky connections are not penalised.
 *
 * The cleanup task evicts expired blocks and records that have seen no
 * activity for 24 hours.
 */
public final class IpFailureTracker {

    private static final long STALE_RECORD_MS = TimeUnit.HOURS.toMillis(24);

    private final Plugin plugin;
    private final ConcurrentHashMap<String, FailureRecord> records = new ConcurrentHashMap<>();

    private volatile PluginConfig.IpBlockConfig config;
    private ScheduledTask cleanupTask;

    public IpFailureTracker(Plugin plugin, PluginConfig.IpBlockConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void applyConfig(PluginConfig.IpBlockConfig config) {
        this.config = config;
    }

    public void startCleanup() {
        cleanupTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin, $ -> cleanup(),
                1, 5, TimeUnit.MINUTES);
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /** True if {@code ip} is currently blocked. Returns false when the block has expired (and evicts the record). */
    public boolean isBlocked(String ip) {
        if (!config.enabled() || ip == null) return false;
        FailureRecord rec = records.get(ip);
        if (rec == null) return false;
        if (rec.blockedUntil() > 0 && rec.blockedUntil() > System.currentTimeMillis()) {
            return true;
        }
        records.remove(ip, rec);
        return false;
    }

    /**
     * Records a Cloudflare-rejected /verify call. Trips the block once the count reaches
     * {@code max-failures}. Counters reset after a previous block has expired.
     */
    public void recordFailure(String ip) {
        if (!config.enabled() || ip == null) return;
        PluginConfig.IpBlockConfig cfg = config;
        long now = System.currentTimeMillis();
        long blockMs = TimeUnit.MINUTES.toMillis(Math.max(1, cfg.blockDurationMinutes()));
        int max = Math.max(1, cfg.maxFailures());

        records.compute(ip, (k, rec) -> {
            if (rec == null || (rec.blockedUntil() > 0 && rec.blockedUntil() <= now)) {
                int newCount = 1;
                long until = newCount >= max ? now + blockMs : 0L;
                if (until > 0) logBlocked(ip, newCount, cfg.blockDurationMinutes());
                return new FailureRecord(newCount, now, until);
            }
            int newCount = rec.count() + 1;
            long until = newCount >= max ? now + blockMs : rec.blockedUntil();
            if (newCount == max) logBlocked(ip, newCount, cfg.blockDurationMinutes());
            return new FailureRecord(newCount, rec.firstFailureAt(), until);
        });
    }

    /** Clears the IP's record on a successful verification — one good attempt forgives prior fails. */
    public void recordSuccess(String ip) {
        if (ip == null) return;
        records.remove(ip);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        long staleBefore = now - STALE_RECORD_MS;
        records.entrySet().removeIf(e -> {
            FailureRecord rec = e.getValue();
            if (rec.blockedUntil() > 0 && rec.blockedUntil() <= now) return true;
            return rec.firstFailureAt() < staleBefore;
        });
    }

    private void logBlocked(String ip, int count, int minutes) {
        plugin.getSLF4JLogger().info(
                "captcha-web blocking ip {} for {} min after {} failed verifications",
                ip, minutes, count);
    }

    private record FailureRecord(int count, long firstFailureAt, long blockedUntil) {}
}
