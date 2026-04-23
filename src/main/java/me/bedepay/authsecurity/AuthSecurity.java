package me.bedepay.authsecurity;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.bedepay.authsecurity.auth.AuthFlow;
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

        AuthFlow authFlow = new AuthFlow(
                this, accounts, config.security(), config.messages(), dialogs, connectionTracker);
        AuthCommands authCommands = new AuthCommands(
                this, accounts, config.messages(), dialogs, config.security());

        getServer().getPluginManager().registerEvents(authFlow, this);
        getServer().getPluginManager().registerEvents(authCommands, this);

        PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.asyncCoordinator())
                .buildOnEnable(this);

        AnnotationParser<CommandSourceStack> parser =
                new AnnotationParser<>(commandManager, CommandSourceStack.class);
        parser.parse(authCommands);
    }

    @Override
    public void onDisable() {
        if (accounts != null) accounts.close();
    }
}
