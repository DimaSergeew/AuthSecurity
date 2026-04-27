package me.bedepay.authsecurity.auth;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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

    private volatile PluginConfig.SecurityConfig security;
    private volatile Messages messages;
    private volatile Dialogs dialogs;

    private final Map<UUID, PendingSession> pending = new ConcurrentHashMap<>();
    private final Map<UUID, String> trustedSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> trustExpiryTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> authenticated = new ConcurrentHashMap<>();

    public AuthFlow(Plugin plugin,
                    AccountRepository accounts,
                    PluginConfig.SecurityConfig security,
                    Messages messages,
                    Dialogs dialogs,
                    ConnectionTracker connectionTracker,
                    LockoutTracker lockoutTracker) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.security = security;
        this.messages = messages;
        this.dialogs = dialogs;
        this.connectionTracker = connectionTracker;
        this.lockoutTracker = lockoutTracker;
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.getOrDefault(uuid, false);
    }

    public void applyConfig(PluginConfig.SecurityConfig security, Messages messages, Dialogs dialogs) {
        this.security = security;
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
                    conn.disconnect(messages.wrongUsernameCase(byName.username()));
                    return;
                }
            } catch (SQLException e) {
                plugin.getSLF4JLogger().error("DB error during username lookup for {}", username, e);
                connectionTracker.release(ip, uuid);
                conn.disconnect(messages.internalError());
                return;
            }
        }

        PendingSession session = account != null
                ? PendingSession.forLogin(account.username(), account.hash(), ip)
                : PendingSession.forRegister(username, ip);

        AuthResult timeoutResult = AuthResult.denied(messages.loginTimedOut());
        session.future().completeOnTimeout(timeoutResult, security.loginTimeoutMinutes(), TimeUnit.MINUTES);
        pending.put(uuid, session);

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
            if (session.isRegister()) {
                plugin.getSLF4JLogger().info("Registered new account: {} ({}) from {}",
                        session.username(), uuid, ip);
            }
        } else {
            connectionTracker.release(ip, uuid);
            conn.getAudience().closeDialog();
            conn.disconnect(result.disconnectReason());
        }
    }

    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        UUID uuid = event.getPlayerUniqueId();
        authenticated.remove(uuid);
        PendingSession session = pending.remove(uuid);
        if (session != null && !session.future().isDone()) {
            session.future().complete(AuthResult.denied(messages.loginCancelled()));
        }
        String ip = event.getIpAddress() == null ? null : event.getIpAddress().getHostAddress();
        connectionTracker.release(ip, uuid);
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

        int remaining = security.maxAttempts() - session.attempts().incrementAndGet();
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
            accounts.upsert(uuid, session.username(), hash, session.ip());
        } catch (SQLException e) {
            plugin.getSLF4JLogger().error("DB error during registration for {}", uuid, e);
            session.future().complete(AuthResult.denied(messages.internalError()));
            return;
        }
        session.future().complete(AuthResult.allowed());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
        return addr != null ? addr.getAddress().getHostAddress() : null;
    }
}
