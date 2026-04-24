package me.bedepay.authsecurity;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.bedepay.authsecurity.auth.AuthFlow;
import me.bedepay.authsecurity.auth.IdleWatcher;
import me.bedepay.authsecurity.auth.LockoutTracker;
import me.bedepay.authsecurity.commands.AdminCommands;
import me.bedepay.authsecurity.commands.AuthCommands;
import me.bedepay.authsecurity.config.ConfigLoader;
import me.bedepay.authsecurity.config.PluginConfig;
import me.bedepay.authsecurity.dialog.Dialogs;
import me.bedepay.authsecurity.ip.ConnectionTracker;
import me.bedepay.authsecurity.storage.AccountRepository;
import me.bedepay.authsecurity.storage.HikariAccountRepository;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;

@SuppressWarnings("UnstableApiUsage")
public final class AuthSecurity extends JavaPlugin {

    private AccountRepository accounts;
    private AuthFlow authFlow;
    private AuthCommands authCommands;
    private AdminCommands adminCommands;
    private LockoutTracker lockoutTracker;
    private IdleWatcher idleWatcher;

    @Override
    public void onEnable() {
        PluginConfig config = ConfigLoader.load(this);

        try {
            accounts = HikariAccountRepository.create(config.database(), getDataFolder());
            accounts.initSchema();
        } catch (Exception e) {
            getSLF4JLogger().error("Fatal: could not initialise database — shutting down", e);
            getServer().shutdown();
            return;
        }

        Dialogs dialogs = new Dialogs(config.messages(), config.support());
        ConnectionTracker connectionTracker = new ConnectionTracker();
        lockoutTracker = new LockoutTracker(config.security().lockout());
        idleWatcher = new IdleWatcher(this, config.security().idleLogout(), config.messages());

        authFlow = new AuthFlow(
                this, accounts, config.security(), config.messages(), dialogs,
                connectionTracker, lockoutTracker);
        authCommands = new AuthCommands(
                this, accounts, authFlow, config.messages(), dialogs, config.security());
        adminCommands = new AdminCommands(
                this, accounts, authFlow, config.messages(), this::reload);

        getServer().getPluginManager().registerEvents(authFlow, this);
        getServer().getPluginManager().registerEvents(authCommands, this);
        getServer().getPluginManager().registerEvents(idleWatcher, this);
        idleWatcher.start();

        PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.asyncCoordinator())
                .buildOnEnable(this);

        AnnotationParser<CommandSourceStack> parser =
                new AnnotationParser<>(commandManager, CommandSourceStack.class);
        parser.parse(authCommands);
        parser.parse(adminCommands);
    }

    @Override
    public void onDisable() {
        if (idleWatcher != null) idleWatcher.stop();
        if (accounts != null) accounts.close();
    }

    /**
     * Reloads the configuration from disk and swaps the live references held by
     * {@link AuthFlow}, {@link AuthCommands}, {@link AdminCommands}, {@link LockoutTracker},
     * and {@link IdleWatcher}. Database settings are NOT reloaded — changing them requires
     * a full server restart because the Hikari pool owns open connections.
     */
    public void reload() {
        PluginConfig config = ConfigLoader.load(this);
        Dialogs dialogs = new Dialogs(config.messages(), config.support());
        if (authFlow != null)      authFlow.applyConfig(config.security(), config.messages(), dialogs);
        if (authCommands != null)  authCommands.applyConfig(config.messages(), dialogs, config.security());
        if (adminCommands != null) adminCommands.applyConfig(config.messages());
        if (lockoutTracker != null) lockoutTracker.applyConfig(config.security().lockout());
        if (idleWatcher != null)   idleWatcher.applyConfig(config.security().idleLogout(), config.messages());
    }
}
