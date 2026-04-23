package me.bedepay.authsecurity.commands;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import me.bedepay.authsecurity.auth.PasswordHasher;
import me.bedepay.authsecurity.config.Messages;
import me.bedepay.authsecurity.config.PluginConfig;
import me.bedepay.authsecurity.dialog.Dialogs;
import me.bedepay.authsecurity.storage.Account;
import me.bedepay.authsecurity.storage.AccountRepository;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("UnstableApiUsage")
public final class AuthCommands implements Listener {

    public static final String PERM_UNREGISTER        = "authsecurity.admin.unregister";
    public static final String PERM_CHANGE_PASS_ADMIN = "authsecurity.admin.changepassword";
    public static final String PERM_ACCOUNT_INFO      = "authsecurity.admin.accountinfo";
    public static final String PERM_CHANGE_PASS       = "authsecurity.changepassword";

    private final Plugin plugin;
    private final AccountRepository accounts;
    private final Messages messages;
    private final Dialogs dialogs;
    private final PluginConfig.SecurityConfig security;

    /** Players who have an open /changepassword dialog. */
    private final ConcurrentHashMap<UUID, Boolean> openChangePassword = new ConcurrentHashMap<>();

    public AuthCommands(Plugin plugin,
                        AccountRepository accounts,
                        Messages messages,
                        Dialogs dialogs,
                        PluginConfig.SecurityConfig security) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.messages = messages;
        this.dialogs = dialogs;
        this.security = security;
    }

    // =========================================================================
    // /unregister <player>
    // =========================================================================

    @Command("unregister <player>")
    @Permission(PERM_UNREGISTER)
    public void unregister(CommandSourceStack source, @Argument("player") String player) {
        Audience audience = source.getSender();
        try {
            Account account = accounts.findByUsername(player);
            if (account == null) {
                audience.sendMessage(messages.commandPlayerNotFound(player));
                return;
            }
            accounts.delete(account.uuid());
            audience.sendMessage(messages.commandUnregisterSuccess(account.username()));
        } catch (SQLException e) {
            plugin.getSLF4JLogger().error("/unregister {} failed", player, e);
            audience.sendMessage(messages.internalError());
        }
    }

    // =========================================================================
    // /changepassword <player> <newpass>    (admin)
    // =========================================================================

    @Command("changepassword <player> <newpass>")
    @Permission(PERM_CHANGE_PASS_ADMIN)
    public void changePasswordAdmin(CommandSourceStack source,
                                    @Argument("player") String player,
                                    @Argument("newpass") String newpass) {
        Audience audience = source.getSender();
        Component err = validatePassword(newpass, newpass);
        if (err != null) {
            audience.sendMessage(err);
            return;
        }
        try {
            Account account = accounts.findByUsername(player);
            if (account == null) {
                audience.sendMessage(messages.commandPlayerNotFound(player));
                return;
            }
            accounts.updateHash(account.uuid(), PasswordHasher.hash(newpass));
            audience.sendMessage(messages.commandChangePasswordAdminSuccess(account.username()));
        } catch (SQLException e) {
            plugin.getSLF4JLogger().error("/changepassword {} failed", player, e);
            audience.sendMessage(messages.internalError());
        }
    }

    // =========================================================================
    // /changepassword                       (player, opens dialog)
    // =========================================================================

    @Command("changepassword")
    @Permission(PERM_CHANGE_PASS)
    public void changePasswordSelf(CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.commandOnlyPlayers());
            return;
        }
        openChangePassword.put(player.getUniqueId(), Boolean.TRUE);
        player.showDialog(dialogs.changePassword(null));
    }

    // =========================================================================
    // /accountinfo <player>
    // =========================================================================

    @Command("accountinfo <player>")
    @Permission(PERM_ACCOUNT_INFO)
    public void accountInfo(CommandSourceStack source, @Argument("player") String player) {
        Audience audience = source.getSender();
        try {
            Account account = accounts.findByUsername(player);
            if (account == null) {
                audience.sendMessage(messages.commandPlayerNotFound(player));
                return;
            }
            audience.sendMessage(messages.commandAccountInfoHeader(account.username()));
            audience.sendMessage(messages.commandAccountInfoLine("uuid",       account.uuid().toString()));
            audience.sendMessage(messages.commandAccountInfoLine("last-ip",    safe(account.lastIp())));
            audience.sendMessage(messages.commandAccountInfoLine("created",    safe(account.createdAt())));
            audience.sendMessage(messages.commandAccountInfoLine("updated",    safe(account.updatedAt())));
        } catch (SQLException e) {
            plugin.getSLF4JLogger().error("/accountinfo {} failed", player, e);
            audience.sendMessage(messages.internalError());
        }
    }

    // =========================================================================
    // /changepassword dialog handling
    // =========================================================================

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection gameConn)) return;
        Player player = gameConn.getPlayer();
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (!openChangePassword.containsKey(uuid)) return;

        Key key = event.getIdentifier();
        if (Dialogs.KEY_CANCEL.equals(key)) {
            openChangePassword.remove(uuid);
            return;
        }
        if (!Dialogs.KEY_SUBMIT_CHANGEPWD.equals(key)) return;

        var view = event.getDialogResponseView();
        if (view == null) return;
        String oldPw   = safeText(view.getText(Dialogs.FIELD_PASSWORD_OLD));
        String newPw   = safeText(view.getText(Dialogs.FIELD_PASSWORD));
        String confirm = safeText(view.getText(Dialogs.FIELD_PASSWORD_CONFIRM));

        Audience audience = player;

        // All blocking I/O (DB + Argon2) on the async scheduler.
        plugin.getServer().getAsyncScheduler().runNow(plugin, $ -> {
            try {
                Account account = accounts.findByUuid(uuid);
                if (account == null) {
                    openChangePassword.remove(uuid);
                    audience.sendMessage(messages.internalError());
                    return;
                }
                if (!PasswordHasher.verify(oldPw, account.hash())) {
                    audience.showDialog(dialogs.changePassword(messages.changePasswordWrongOld()));
                    return;
                }
                Component err = validatePassword(newPw, confirm);
                if (err != null) {
                    audience.showDialog(dialogs.changePassword(err));
                    return;
                }
                accounts.updateHash(uuid, PasswordHasher.hash(newPw));
                openChangePassword.remove(uuid);
                audience.closeDialog();
                audience.sendMessage(messages.changePasswordSuccess());
            } catch (SQLException e) {
                openChangePassword.remove(uuid);
                plugin.getSLF4JLogger().error("change-password failed for {}", uuid, e);
                audience.sendMessage(messages.internalError());
            }
        });
    }

    private Component validatePassword(String pw, String confirm) {
        if (pw == null || pw.isBlank()) return messages.passwordEmpty();
        if (pw.length() < security.passwordMinLength()) return messages.passwordTooShort(security.passwordMinLength());
        if (pw.length() > security.passwordMaxLength()) return messages.passwordTooLong(security.passwordMaxLength());
        if (!pw.equals(confirm)) return messages.passwordsMismatch();
        return null;
    }

    private static String safe(Object value) {
        return value == null ? "(none)" : value.toString();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
