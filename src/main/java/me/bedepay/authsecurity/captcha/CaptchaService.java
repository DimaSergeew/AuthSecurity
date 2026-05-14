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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Issues short-lived captcha verification tokens, verifies Turnstile responses
 * against Cloudflare's siteverify endpoint, and persists the result so the
 * AuthFlow gate can read it.
 */
public final class CaptchaService {

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final Pattern SUCCESS_REGEX = Pattern.compile("\"success\"\\s*:\\s*true\\b");

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

    /**
     * Per-token attempt counter. Limits how many times a single token can be hammered
     * against /verify before further attempts short-circuit to REJECTED. Cleared on
     * a successful verification or when the waiter is cancelled (timeout, disconnect).
     */
    private final Map<String, AtomicInteger> tokenAttempts = new ConcurrentHashMap<>();

    /**
     * Token → (uuid, username) so /verify outcomes can be logged with player identity
     * instead of an opaque token. Populated by {@link #issueToken}, removed on success
     * or {@link #cancelWaiter}.
     */
    private final Map<String, TokenIdentity> tokenIdentities = new ConcurrentHashMap<>();

    public record TokenIdentity(UUID uuid, String username) {}

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
    public String issueToken(UUID uuid, String username) {
        byte[] buf = new byte[32];
        random.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        long ttlSeconds = TimeUnit.MINUTES.toSeconds(Math.max(1, config.tokenTtlMinutes()));
        try {
            accounts.insertCaptchaToken(token, uuid, username, ttlSeconds);
            tokenIdentities.put(token, new TokenIdentity(uuid, username));
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
        tokenAttempts.remove(token);
        tokenIdentities.remove(token);
    }

    /**
     * Calls Cloudflare's siteverify asynchronously and, on success, marks the token verified
     * in the DB. The returned future never completes exceptionally — outcomes are signalled
     * via {@link VerificationOutcome}:
     * <ul>
     *   <li>{@code SUCCESS}  — Cloudflare confirmed and DB row updated.</li>
     *   <li>{@code REJECTED} — Cloudflare returned success:false, or the token exceeded
     *       its per-token attempt cap. Caller should count this against rate-limit thresholds.</li>
     *   <li>{@code ERROR}    — network, HTTP, or DB error. Player should not be penalised.</li>
     * </ul>
     *
     * Async on purpose: this is invoked from the captcha web server's HTTP handler, and a
     * synchronous send would block a Jetty worker for up to {@code timeout} per request,
     * making the gate easy to DoS by hammering /verify with junk responses.
     */
    public CompletableFuture<VerificationOutcome> markVerified(String token, String cfResponse) {
        if (token == null || token.isBlank() || cfResponse == null || cfResponse.isBlank()) {
            return CompletableFuture.completedFuture(VerificationOutcome.REJECTED);
        }
        TokenIdentity who = tokenIdentities.get(token);
        int max = Math.max(1, config.maxAttemptsPerToken());
        int attempts = tokenAttempts.computeIfAbsent(token, k -> new AtomicInteger()).incrementAndGet();
        if (attempts > max) {
            plugin.getSLF4JLogger().info(
                    "Captcha REJECTED for {}: token exceeded max-attempts-per-token ({})",
                    who(who), max);
            return CompletableFuture.completedFuture(VerificationOutcome.REJECTED);
        }
        String form = "secret=" + enc(config.secretKey())
                + "&response=" + enc(cfResponse);
        HttpRequest req = HttpRequest.newBuilder(URI.create(SITEVERIFY_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null) {
                        plugin.getSLF4JLogger().warn("Captcha ERROR for {}: siteverify call failed", who(who), err);
                        return VerificationOutcome.ERROR;
                    }
                    if (resp.statusCode() != 200) {
                        plugin.getSLF4JLogger().warn(
                                "Captcha ERROR for {}: siteverify returned HTTP {}",
                                who(who), resp.statusCode());
                        return VerificationOutcome.ERROR;
                    }
                    String body = resp.body();
                    boolean cloudflareOk = body != null && SUCCESS_REGEX.matcher(body).find();
                    if (!cloudflareOk) {
                        plugin.getSLF4JLogger().info(
                                "Captcha REJECTED for {} by Cloudflare: {}",
                                who(who), body);
                        return VerificationOutcome.REJECTED;
                    }
                    try {
                        boolean updated = accounts.markCaptchaVerified(token);
                        if (updated) {
                            tokenAttempts.remove(token);
                            tokenIdentities.remove(token);
                            Runnable cb = verifyCallbacks.remove(token);
                            if (cb != null) {
                                try {
                                    cb.run();
                                } catch (Exception cbErr) {
                                    plugin.getSLF4JLogger().warn(
                                            "Captcha verify callback failed for {}", who(who), cbErr);
                                }
                            }
                            plugin.getSLF4JLogger().info("Captcha SUCCESS for {}", who(who));
                            return VerificationOutcome.SUCCESS;
                        }
                        // DB returned 0 rows updated — token expired or absent. Don't punish the player.
                        plugin.getSLF4JLogger().info(
                                "Captcha verify for {} had no effect (token expired or already consumed)",
                                who(who));
                        return VerificationOutcome.ERROR;
                    } catch (SQLException dbErr) {
                        plugin.getSLF4JLogger().warn(
                                "Captcha ERROR for {}: DB failure while marking verified",
                                who(who), dbErr);
                        return VerificationOutcome.ERROR;
                    }
                });
    }

    private static String who(TokenIdentity id) {
        return id == null ? "unknown-token" : id.username() + " (" + id.uuid() + ")";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
