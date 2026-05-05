package me.bedepay.authsecurity.commands;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.bedepay.authsecurity.auth.AuthFlow;
import me.bedepay.authsecurity.config.Messages;
import me.bedepay.authsecurity.storage.Account;
import me.bedepay.authsecurity.storage.AccountRepository;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

import java.sql.SQLException;

@SuppressWarnings("UnstableApiUsage")
public final class AdminCommands {

    public static final String PERM_RELOAD = "authsecurity.admin.reload";
    public static final String PERM_LOGOUT = "authsecurity.admin.logout";

    private final Plugin plugin;
    private final AccountRepository accounts;
    private final AuthFlow authFlow;
    private final Runnable reloadCallback;

    private volatile Messages messages;

    public AdminCommands(Plugin plugin,
                         AccountRepository accounts,
                         AuthFlow authFlow,
                         Messages messages,
                         Runnable reloadCallback) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.authFlow = authFlow;
        this.messages = messages;
        this.reloadCallback = reloadCallback;
    }

    public void applyConfig(Messages messages) {
        this.messages = messages;
    }

    @Command("authsecurity reload")
    @Permission(PERM_RELOAD)
    public void reload(CommandSourceStack source) {
        Audience audience = source.getSender();
        audience.sendMessage(messages.commandReloadStarted());
        try {
            reloadCallback.run();
            audience.sendMessage(messages.commandReloadSuccess());
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("/authsecurity reload failed", e);
            audience.sendMessage(messages.commandReloadFailed());
        }
    }

    @Command("authsecurity logout <player>")
    @Permission(PERM_LOGOUT)
    public void logout(CommandSourceStack source,
                       @Argument(value = "player", suggestions = AuthCommands.SUGGEST_PLAYERS) String player) {
        CommandSender sender = source.getSender();
        plugin.getServer().getAsyncScheduler().runNow(plugin, $ -> {
            try {
                Account account = accounts.findByUsername(player);
                if (account == null) {
                    runForSender(sender, () -> sender.sendMessage(messages.commandPlayerNotFound(player)));
                    return;
                }

                authFlow.invalidate(account.uuid());
                Player online = plugin.getServer().getPlayer(account.uuid());
                if (online != null) {
                    kick(online, messages.commandLogoutKick());
                    runForSender(sender, () -> sender.sendMessage(messages.commandLogoutSuccess(account.username())));
                } else {
                    runForSender(sender, () -> sender.sendMessage(messages.commandLogoutNotOnline(account.username())));
                }
                plugin.getSLF4JLogger().info("Logged out {} ({}), requester={}",
                        account.username(), account.uuid(), sender.getName());
            } catch (SQLException e) {
                plugin.getSLF4JLogger().error("/authsecurity logout {} failed", player, e);
                runForSender(sender, () -> sender.sendMessage(messages.internalError()));
            }
        });
    }

    private void runForSender(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, $ -> action.run(), null);
        } else {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, $ -> action.run());
        }
    }

    private void kick(Player player, Component reason) {
        player.getScheduler().run(plugin, $ -> player.kick(reason), null);
    }
}
