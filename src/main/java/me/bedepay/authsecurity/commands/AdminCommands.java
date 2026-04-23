package me.bedepay.authsecurity.commands;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.bedepay.authsecurity.config.Messages;
import net.kyori.adventure.audience.Audience;
import org.bukkit.plugin.Plugin;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

@SuppressWarnings("UnstableApiUsage")
public final class AdminCommands {

    public static final String PERM_RELOAD = "authsecurity.admin.reload";

    private final Plugin plugin;
    private final Runnable reloadCallback;

    private volatile Messages messages;

    public AdminCommands(Plugin plugin, Messages messages, Runnable reloadCallback) {
        this.plugin = plugin;
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
}
