package me.bedepay.authsecurity.captcha;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.bedepay.authsecurity.config.PluginConfig;
import me.bedepay.authsecurity.storage.AccountRepository;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Issues short-lived captcha verification tokens, verifies Turnstile responses
 * against Cloudflare's siteverify endpoint, and persists the result so the
 * AuthFlow gate can read it.
 */
public final class CaptchaService {

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final Plugin plugin;
    private final AccountRepository accounts;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    private volatile PluginConfig.CaptchaConfig config;
    private ScheduledTask cleanupTask;

    /**
     * Push-style waiters keyed by token. AuthFlow registers a callback before showing
     * the captcha dialog; {@link #markVerified} fires it as soon as Cloudflare confirms
     * success, which lets the configuration-phase gate unblock without the player
     * having to come back into Minecraft and click anything.
     */
    private final Map<String, Runnable> verifyCallbacks = new ConcurrentHashMap<>();

    public CaptchaService(Plugin plugin, AccountRepository accounts, PluginConfig.CaptchaConfig config) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void applyConfig(PluginConfig.CaptchaConfig config) {
        this.config = config;
    }

    public PluginConfig.CaptchaConfig config() {
        return config;
    }

    public void startCleanup() {
        cleanupTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin,
                $ -> {
                    try {
                        accounts.deleteExpiredCaptchaTokens();
                    } catch (SQLException e) {
                        plugin.getSLF4JLogger().warn("captcha cleanup failed", e);
                    }
                },
                10, 10, TimeUnit.MINUTES);
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /**
     * Generates a 32-byte random token (base64url, ~43 chars) and persists it.
     * Returns the token string the player must visit, or {@code null} on DB error.
     */
    public String issueToken(UUID uuid, String username, String ip) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        long ttlSeconds = TimeUnit.MINUTES.toSeconds(Math.max(1, config.tokenTtlMinutes()));
        try {
            accounts.insertCaptchaToken(token, uuid, username, ip, ttlSeconds);
            return token;
        } catch (SQLException e) {
            plugin.getSLF4JLogger().error("Failed to insert captcha token for {}", uuid, e);
            return null;
        }
    }

    /**
     * Register a one-shot callback that fires the moment {@code token} is verified.
     * Replaces any previously-registered waiter for the same token.
     */
    public void registerWaiter(String token, Runnable onVerified) {
        if (token == null) return;
        verifyCallbacks.put(token, onVerified);
    }

    /**
     * Removes a pending waiter (e.g. on timeout, disconnect, or cancel).
     */
    public void cancelWaiter(String token) {
        if (token == null) return;
        verifyCallbacks.remove(token);
    }

    public boolean isVerified(String token) {
        try {
            return accounts.isCaptchaVerified(token);
        } catch (SQLException e) {
            plugin.getSLF4JLogger().warn("DB error reading captcha verified flag", e);
            return false;
        }
    }

    /**
     * Calls Cloudflare's siteverify and, on success, marks the token verified in the DB.
     * Returns {@code true} on full success, {@code false} on any failure.
     */
    public boolean markVerified(String token, String cfResponse, String clientIp) {
        if (token == null || token.isBlank() || cfResponse == null || cfResponse.isBlank()) {
            return false;
        }
        try {
            String form = "secret=" + enc(config.secretKey())
                    + "&response=" + enc(cfResponse)
                    + (clientIp != null ? "&remoteip=" + enc(clientIp) : "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(SITEVERIFY_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                plugin.getSLF4JLogger().warn("Turnstile siteverify returned HTTP {}", resp.statusCode());
                return false;
            }
            // Body is JSON like {"success": true, ...}. We only care about the success boolean.
            // Avoid pulling in a JSON dep for one field.
            String body = resp.body();
            boolean success = body != null && body.contains("\"success\":true");
            if (!success) {
                plugin.getSLF4JLogger().info("Turnstile rejected response: {}", body);
                return false;
            }
            boolean updated = accounts.markCaptchaVerified(token);
            if (updated) {
                Runnable cb = verifyCallbacks.remove(token);
                if (cb != null) {
                    try {
                        cb.run();
                    } catch (Exception cbErr) {
                        plugin.getSLF4JLogger().warn("Captcha verify callback failed for token", cbErr);
                    }
                }
            }
            return updated;
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Turnstile siteverify call failed", e);
            return false;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
