package me.bedepay.authsecurity.auth;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.bedepay.authsecurity.captcha.CaptchaService;
import me.bedepay.authsecurity.config.Messages;
import me.bedepay.authsecurity.config.PluginConfig;
import me.bedepay.authsecurity.dialog.Dialogs;
import me.bedepay.authsecurity.ip.ConnectionTracker;
import me.bedepay.authsecurity.storage.Account;
import me.bedepay.authsecurity.storage.AccountRepository;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("UnstableApiUsage")
public final class AuthFlow implements Listener {

    private final Plugin plugin;
    private final AccountRepository accounts;
    private final ConnectionTracker connectionTracker;
    private final LockoutTracker lockoutTracker;
    private final CaptchaService captchaService;

    private volatile PluginConfig.SecurityConfig security;
    private volatile PluginConfig.CaptchaConfig captchaConfig;
    private volatile Messages messages;
    private volatile Dialogs dialogs;

    private final Map<UUID, PendingSession> pending = new ConcurrentHashMap<>();
    private final Map<UUID, String> trustedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> trustExpiryTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> authenticated = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger captchaInflight =
            new java.util.concurrent.atomic.AtomicInteger();

    public AuthFlow(Plugin plugin,
                    AccountRepository accounts,
                    PluginConfig.SecurityConfig security,
                    PluginConfig.CaptchaConfig captchaConfig,
                    Messages messages,
                    Dialogs dialogs,
                    ConnectionTracker connectionTracker,
                    LockoutTracker lockoutTracker,
                    CaptchaService captchaService) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.security = security;
        this.captchaConfig = captchaConfig;
        this.messages = messages;
        this.dialogs = dialogs;
        this.connectionTracker = connectionTracker;
        this.lockoutTracker = lockoutTracker;
        this.captchaService = captchaService;
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.getOrDefault(uuid, false);
    }

    public void applyConfig(PluginConfig.SecurityConfig security,
                            PluginConfig.CaptchaConfig captchaConfig,
                            Messages messages,
                            Dialogs dialogs) {
        this.security = security;
        this.captchaConfig = captchaConfig;
        this.messages = messages;
        this.dialogs = dialogs;
    }

    /**
     * Drops any trusted IP session and authentication flag for {@code uuid}.
     * Called on password change and on {@code /authsecurity logout}.
     */
    public void invalidate(UUID uuid) {
        trustedSessions.remove(uuid);
        authenticated.remove(uuid);
        ScheduledTask task = trustExpiryTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    // =========================================================================
    // Configuration-phase gate
    // =========================================================================

    @EventHandler(priority = EventPriority.NORMAL)
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection conn = event.getConnection();
        UUID uuid = conn.getProfile().getId();
        if (uuid == null) {
            conn.disconnect(messages.uuidMissing());
            return;
        }

        String ip = extractIp(conn);

        if (!connectionTracker.tryAcquire(ip, uuid, security.accountsPerIpLimit())) {
            conn.disconnect(messages.ipLimitReached(security.accountsPerIpLimit()));
            return;
        }

        if (security.sessionTrustEnabled() && ip != null && ip.equals(trustedSessions.get(uuid))) {
            authenticated.put(uuid, true);
            // Renew the TTL so the trusted entry doesn't disappear mid-session if the
            // player reconnected close to expiry — otherwise the next reconnect would
            // unexpectedly require a full login despite ongoing activity.
            scheduleTrustExpiry(uuid);
            return;
        }

        if (ip != null) {
            long lockMinutes = lockoutTracker.remainingLockMinutes(ip);
            if (lockMinutes > 0) {
                connectionTracker.release(ip, uuid);
                conn.disconnect(messages.accountLocked(lockMinutes));
                return;
            }
        }

        String username = conn.getProfile().getName();
        Account account;
        try {
            account = accounts.findByUuid(uuid);
        } catch (SQLException e) {
            plugin.getSLF4JLogger().error("DB error during account lookup for {}", uuid, e);
            connectionTracker.release(ip, uuid);
            conn.disconnect(messages.internalError());
            return;
        }

        if (account == null && username != null) {
            try {
                Account byName = accounts.findByUsername(username);
                if (byName != null) {
                    connectionTracker.release(ip, uuid);
                    if (byName.username().equals(username)) {
                        plugin.getSLF4JLogger().warn(
                                "Name collision: '{}' is registered to UUID {} but {} connected with the same name",
                                username, byName.uuid(), uuid);
                        conn.disconnect(messages.nameAlreadyRegistered(byName.username()));
                    } else {
                        conn.disconnect(messages.wrongUsernameCase(byName.username()));
                    }
                    return;
                }
            } catch (SQLException e) {
                plugin.getSLF4JLogger().error("DB error during username lookup for {}", username, e);
                connectionTracker.release(ip, uuid);
                conn.disconnect(messages.internalError());
                return;
            }
        }

        // ---- Captcha gate (new players + returning players whose verification expired) ----
        if (captchaConfig.enabled() && captchaRequired(account, ip)) {
            // Global cap on simultaneously-waiting captcha challenges. Each one holds
            // a Paper async config-event thread for up to token-ttl-minutes, so without
            // a ceiling a botnet could exhaust the pool and starve legitimate joins.
            int max = captchaConfig.maxConcurrentChallenges();
            // When the cap is disabled (max <= 0) we skip the inflight counter entirely,
            // otherwise the unconditional decrement in finally would drive it negative
            // and silently raise the cap after a later reload that re-enables the limit.
            boolean tracked = max > 0;
            if (tracked && captchaInflight.incrementAndGet() > max) {
                captchaInflight.decrementAndGet();
                connectionTracker.release(ip, uuid);
                conn.disconnect(messages.captchaServerBusy());
                return;
            }
            try {
                boolean welcome = (account == null);
                String token = captchaService.issueToken(uuid, username, ip);
                if (token == null) {
                    connectionTracker.release(ip, uuid);
                    conn.disconnect(messages.captchaIssueError());
                    return;
                }
                String url = buildCaptchaUrl(token);

                PendingSession captchaSession = PendingSession.forCaptcha(username, ip, token, !welcome);
                captchaSession.future().completeOnTimeout(
                        AuthResult.denied(messages.loginTimedOut()),
                        Math.max(1, captchaConfig.tokenTtlMinutes()),
                        TimeUnit.MINUTES);
                replacePending(uuid, captchaSession);

                // Push-callback: when the player solves the Turnstile challenge in their
                // browser, CaptchaService.markVerified fires this from the Javalin thread,
                // which unblocks the future().join() below — the player doesn't have to
                // click anything in Minecraft to continue.
                captchaService.registerWaiter(token,
                        () -> captchaSession.future().complete(AuthResult.allowed()));

                conn.getAudience().showDialog(dialogs.captcha(url, username, welcome, null));

                AuthResult captchaResult;
                try {
                    captchaResult = captchaSession.future().join();
                } finally {
                    pending.remove(uuid);
                    captchaService.cancelWaiter(token);
                }

                if (!captchaResult.ok()) {
                    connectionTracker.release(ip, uuid);
                    closeDialogAndDisconnect(conn, captchaResult.disconnectReason());
                    return;
                }
                // Captcha passed — close the captcha dialog so the next showDialog
                // (login/register) opens cleanly on the client.
                conn.getAudience().closeDialog();
            } finally {
                if (tracked) captchaInflight.decrementAndGet();
            }
        }

        PendingSession session = account != null
                ? PendingSession.forLogin(account.username(), account.hash(), ip)
                : PendingSession.forRegister(username, ip);

        AuthResult timeoutResult = AuthResult.denied(messages.loginTimedOut());
        session.future().completeOnTimeout(timeoutResult, security.loginTimeoutMinutes(), TimeUnit.MINUTES);
        replacePending(uuid, session);

        conn.getAudience().showDialog(session.isRegister()
                ? dialogs.register(session.username(), null)
                : dialogs.login(session.username(), null));

        AuthResult result;
        try {
            result = session.future().join();
        } finally {
            pending.remove(uuid);
        }

        if (result.ok()) {
            authenticated.put(uuid, true);
            if (security.sessionTrustEnabled() && ip != null) {
                trustedSessions.put(uuid, ip);
                scheduleTrustExpiry(uuid);
            }
            try {
                accounts.updateLastIp(uuid, ip);
            } catch (SQLException e) {
                plugin.getSLF4JLogger().warn("Failed to record last IP for {}", uuid, e);
            }
            if (captchaConfig.enabled()) {
                try {
                    accounts.touchCaptchaVerifiedAt(uuid, ip);
                } catch (SQLException e) {
                    plugin.getSLF4JLogger().warn("Failed to record captcha_verified_at for {}", uuid, e);
                }
            }
            if (session.isRegister()) {
                plugin.getSLF4JLogger().info("Registered new account: {} ({}) from {}",
                        session.username(), uuid, ip);
            } else {
                plugin.getSLF4JLogger().info("Login: {} ({}) from {}",
                        session.username(), uuid, ip);
            }
        } else {
            connectionTracker.release(ip, uuid);
            closeDialogAndDisconnect(conn, result.disconnectReason());
        }
    }

    /**
     * Closes any open dialog and disconnects the connection. The brief sleep gives
     * Netty time to flush the {@code clear_dialog} packet before the disconnect
     * packet closes the channel — without it the kick (e.g. login-timeout) lands on
     * the client with the dialog still painted over the disconnect screen.
     */
    private static void closeDialogAndDisconnect(PlayerConfigurationConnection conn, Component reason) {
        conn.getAudience().closeDialog();
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        conn.disconnect(reason);
    }

    /**
     * Reconnect race: PlayerConnectionCloseEvent only carries UUID/IP, not the underlying
     * connection. If a player closes and reconnects fast, the close for the OLD connection
     * may fire AFTER the configure handler for the NEW connection has already inserted its
     * session into {@link #pending}. Blindly removing/cancelling that entry would kick the
     * fresh player with "Login cancelled".
     *
     * Mitigation: only cancel the pending session if it has been parked long enough that it
     * almost certainly belongs to the closing connection. A session younger than this window
     * is assumed to be for the new connection and is left alone — its own close (if it ever
     * comes) will fire later, or its future will time out / be replaced normally.
     */
    private static final long PENDING_CLOSE_GRACE_MILLIS = 250;

    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        UUID uuid = event.getPlayerUniqueId();
        authenticated.remove(uuid);
        long now = System.currentTimeMillis();
        pending.compute(uuid, (k, sess) -> {
            if (sess == null) return null;
            if (now - sess.createdAtMillis() < PENDING_CLOSE_GRACE_MILLIS) {
                return sess;
            }
            if (!sess.future().isDone()) {
                sess.future().complete(AuthResult.denied(messages.loginCancelled()));
            }
            return null;
        });
        String ip = event.getIpAddress() == null ? null : event.getIpAddress().getHostAddress();
        connectionTracker.release(ip, uuid);
    }

    /**
     * Atomically install {@code session} as the pending entry for {@code uuid}, completing
     * any previously parked session's future so its configure thread can unwind. Without this
     * a stale session from a previous connection would leak: its future would never complete
     * (close-handler can't tell it apart from the new one — see {@link #onConnectionClose}),
     * leaving the old async config-event thread blocked until {@code login-timeout-minutes}.
     */
    private void replacePending(UUID uuid, PendingSession session) {
        PendingSession previous = pending.put(uuid, session);
        if (previous != null && previous != session && !previous.future().isDone()) {
            if (previous.captchaToken() != null) {
                captchaService.cancelWaiter(previous.captchaToken());
            }
            previous.future().complete(AuthResult.denied(messages.loginCancelled()));
        }
    }

    // =========================================================================
    // Dialog click handler
    // =========================================================================

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection configConn)) return;

        UUID uuid = configConn.getProfile().getId();
        if (uuid == null) return;

        PendingSession session = pending.get(uuid);
        if (session == null || session.future().isDone()) return;

        Key key = event.getIdentifier();
        Audience audience = configConn.getAudience();
        DialogResponseView view = event.getDialogResponseView();

        if (Dialogs.KEY_CANCEL.equals(key)) {
            session.future().complete(AuthResult.denied(messages.loginCancelled()));
            return;
        }

        if (Dialogs.KEY_FORGOT_BACK.equals(key)) {
            audience.showDialog(dialogs.login(session.username(), null));
            return;
        }

        if (Dialogs.KEY_SUBMIT_LOGIN.equals(key)) {
            handleLogin(uuid, session, audience, text(view, Dialogs.FIELD_PASSWORD));
        } else if (Dialogs.KEY_SUBMIT_REGISTER.equals(key)) {
            handleRegister(uuid, session, audience,
                    text(view, Dialogs.FIELD_PASSWORD),
                    text(view, Dialogs.FIELD_PASSWORD_CONFIRM));
        }
    }

    // =========================================================================
    // Auth logic
    // =========================================================================

    private void handleLogin(UUID uuid, PendingSession session, Audience audience, String password) {
        if (PasswordHasher.verify(password, session.hash())) {
            session.future().complete(AuthResult.allowed());
            return;
        }

        boolean locked = session.ip() != null && lockoutTracker.recordFailure(session.ip());
        if (locked) {
            long remaining = session.ip() != null ? lockoutTracker.remainingLockMinutes(session.ip()) : 0;
            plugin.getSLF4JLogger().info("IP locked after repeated failures: {} ({}) from {}",
                    session.username(), uuid, session.ip());
            session.future().complete(AuthResult.denied(messages.accountLocked(remaining)));
            return;
        }

        int attempts = session.attempts().incrementAndGet();
        int remaining = security.maxAttempts() - attempts;
        plugin.getSLF4JLogger().info("Failed login: {} ({}) from {}, attempts={}/{}",
                session.username(), uuid, session.ip(), attempts, security.maxAttempts());
        if (remaining <= 0) {
            session.future().complete(AuthResult.denied(messages.tooManyAttempts()));
            return;
        }
        audience.showDialog(dialogs.login(session.username(), messages.wrongPassword(remaining)));
    }

    private void handleRegister(UUID uuid, PendingSession session, Audience audience,
                                String password, String confirm) {
        Component error = PasswordPolicy.validate(password, confirm, security, messages);
        if (error != null) {
            audience.showDialog(dialogs.register(session.username(), error));
            return;
        }

        String hash = PasswordHasher.hash(password);
        try {
            accounts.insert(uuid, session.username(), hash, session.ip());
        } catch (SQLException e) {
            plugin.getSLF4JLogger().error("DB error during registration for {}", uuid, e);
            session.future().complete(AuthResult.denied(messages.internalError()));
            return;
        }
        session.future().complete(AuthResult.allowed());
    }

    private boolean captchaRequired(Account account, String ip) {
        if (account == null) return true;
        int validityDays = captchaConfig.verificationValidityDays();
        if (validityDays <= 0) return true;
        Timestamp ts = account.captchaVerifiedAt();
        if (ts == null) return true;
        long ageMillis = System.currentTimeMillis() - ts.getTime();
        if (ageMillis > TimeUnit.DAYS.toMillis(validityDays)) return true;
        if (captchaConfig.revalidateOnIpChange()) {
            String verifiedIp = account.captchaVerifiedIp();
            // No recorded verification IP (legacy account verified before this column existed) —
            // re-prompt once, the next success will fill it in.
            if (verifiedIp == null || verifiedIp.isBlank()) return true;
            if (ip == null || !verifiedIp.equals(ip)) return true;
        }
        return false;
    }

    private String buildCaptchaUrl(String token) {
        String base = captchaConfig.publicBaseUrl();
        if (base == null || base.isBlank()) base = "http://127.0.0.1:" + captchaConfig.webPort();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/c/" + token;
    }

    private void scheduleTrustExpiry(UUID uuid) {
        ScheduledTask previous = trustExpiryTasks.remove(uuid);
        if (previous != null) previous.cancel();

        ScheduledTask task = plugin.getServer().getAsyncScheduler().runDelayed(
                plugin, $ -> {
                    trustedSessions.remove(uuid);
                    trustExpiryTasks.remove(uuid);
                },
                security.sessionTtlMinutes(), TimeUnit.MINUTES);
        trustExpiryTasks.put(uuid, task);
    }

    private static String text(DialogResponseView view, String key) {
        if (view == null) return "";
        String value = view.getText(key);
        return value == null ? "" : value;
    }

    private static String extractIp(PlayerConfigurationConnection conn) {
        InetSocketAddress addr = conn.getClientAddress();
        if (addr == null) return null;
        java.net.InetAddress a = addr.getAddress();
        return a != null ? a.getHostAddress() : addr.getHostString();
    }
}
