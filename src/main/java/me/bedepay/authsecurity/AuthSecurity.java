package me.bedepay.authsecurity;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.bedepay.authsecurity.auth.AuthFlow;
import me.bedepay.authsecurity.captcha.CaptchaService;
import me.bedepay.authsecurity.captcha.CaptchaWebServer;
import me.bedepay.authsecurity.captcha.IpFailureTracker;
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
    private CaptchaService captchaService;
    private CaptchaWebServer captchaWebServer;
    private IpFailureTracker captchaFailures;

    @Override
    public void onEnable() {
        saveResource("README.md", false);

        PluginConfig config;
        try {
            config = ConfigLoader.load(this);
        } catch (Exception e) {
            getSLF4JLogger().error("Fatal: invalid AuthSecurity config.yml — fix the config and restart the server", e);
            getServer().shutdown();
            return;
        }

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

        captchaService = new CaptchaService(this, accounts, config.captcha());
        captchaFailures = new IpFailureTracker(this, config.captcha().ipBlock());
        if (config.captcha().enabled()) {
            try {
                captchaWebServer = new CaptchaWebServer(this, captchaService, captchaFailures);
                captchaWebServer.start();
                captchaService.startCleanup();
                captchaFailures.startCleanup();
            } catch (Exception e) {
                getSLF4JLogger().error("""
                        Fatal: captcha is enabled, but the captcha web server failed to start.
                        Check captcha.web-bind, captcha.web-port and whether the port is already used.
                        Server will shut down to avoid a broken authentication flow.
                        """, e);
                getServer().shutdown();
                return;
            }
        }

        authFlow = new AuthFlow(
                this, accounts, config.security(), config.captcha(),
                config.messages(), dialogs,
                connectionTracker, captchaService);
        authCommands = new AuthCommands(
                this, accounts, authFlow, config.messages(), dialogs, config.security());
        adminCommands = new AdminCommands(
                this, accounts, authFlow, config.messages(), this::reload);

        getServer().getPluginManager().registerEvents(authFlow, this);
        getServer().getPluginManager().registerEvents(authCommands, this);

        PaperCommandManager<CommandSourceStack> commandManager = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
                .buildOnEnable(this);

        AnnotationParser<CommandSourceStack> parser =
                new AnnotationParser<>(commandManager, CommandSourceStack.class);
        parser.parse(authCommands);
        parser.parse(adminCommands);
    }

    @Override
    public void onDisable() {
        if (captchaService != null) captchaService.stop();
        if (captchaFailures != null) captchaFailures.stop();
        if (captchaWebServer != null) captchaWebServer.stop();
        if (accounts != null) accounts.close();
    }

    /**
     * Reloads the configuration from disk and swaps the live references held by
     * {@link AuthFlow}, {@link AuthCommands}, and {@link AdminCommands}. Database settings
     * and the captcha web server are NOT reloaded — changing those requires a full server
     * restart because they own long-lived resources (connection pool, listening port).
     */
    public void reload() {
        PluginConfig config = ConfigLoader.load(this);
        Dialogs dialogs = new Dialogs(config.messages(), config.support());
        if (authFlow != null)      authFlow.applyConfig(config.security(), config.captcha(), config.messages(), dialogs);
        if (authCommands != null)  authCommands.applyConfig(config.messages(), dialogs, config.security());
        if (adminCommands != null) adminCommands.applyConfig(config.messages());
        if (captchaService != null) captchaService.applyConfig(config.captcha());
        if (captchaFailures != null) captchaFailures.applyConfig(config.captcha().ipBlock());
    }
}
