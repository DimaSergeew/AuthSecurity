package me.bedepay.authsecurity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Copies the bundled default config on first run and parses it into a {@link PluginConfig}.
 */
public final class ConfigLoader {

    private static final int CONFIG_VERSION = 2;

    private ConfigLoader() {}

    public static PluginConfig load(Plugin plugin) {
        plugin.getDataFolder().mkdirs();
        File target = new File(plugin.getDataFolder(), "config.yml");
        if (!target.exists()) {
            copyDefaultConfig(plugin, target);
        } else {
            rotateOutdatedConfig(plugin, target);
        }

        FileConfiguration yml = YamlConfiguration.loadConfiguration(target);
        FileConfiguration defaults = loadBundledDefaults(plugin);
        if (defaults != null) yml.setDefaults(defaults);

        PluginConfig config = new PluginConfig(
                readDatabase(section(yml, "database")),
                readSecurity(section(yml, "security")),
                readSupport(section(yml, "support")),
                readCaptcha(yml.getConfigurationSection("captcha")),
                readMessages(section(yml, "messages"))
        );
        validate(config);
        return config;
    }

    private static void rotateOutdatedConfig(Plugin plugin, File target) {
        FileConfiguration existing = YamlConfiguration.loadConfiguration(target);
        int existingVersion = existing.getInt("config-version", 0);
        if (existingVersion == CONFIG_VERSION) return;

        File backup = nextBackupFile(target);
        try {
            Files.move(target.toPath(), backup.toPath());
            copyDefaultConfig(plugin, target);
            plugin.getSLF4JLogger().warn(
                    "AuthSecurity config.yml was updated from version {} to {}. Old config saved as {}",
                    existingVersion, CONFIG_VERSION, backup.getName());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rotate outdated config.yml to " + backup.getName(), e);
        }
    }

    private static File nextBackupFile(File target) {
        File dir = target.getParentFile();
        File backup = new File(dir, target.getName() + ".old");
        int index = 1;
        while (backup.exists()) {
            backup = new File(dir, target.getName() + ".old." + index++);
        }
        return backup;
    }

    private static void copyDefaultConfig(Plugin plugin, File target) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) throw new IllegalStateException("config.yml missing from plugin jar");
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write default config.yml", e);
        }
    }

    private static FileConfiguration loadBundledDefaults(Plugin plugin) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection sub = parent.getConfigurationSection(path);
        if (sub != null) return sub;
        throw new IllegalStateException("Missing configuration section: " + path);
    }

    private static PluginConfig.DatabaseConfig readDatabase(ConfigurationSection s) {
        ConfigurationSection h2 = section(s, "h2");
        ConfigurationSection md = section(s, "mariadb");
        ConfigurationSection pool = section(s, "pool");
        return new PluginConfig.DatabaseConfig(
                s.getString("type", "h2"),
                new PluginConfig.H2Settings(
                        h2.getString("file", "players"),
                        h2.getString("options", "AUTO_SERVER=FALSE;MODE=MySQL")
                ),
                new PluginConfig.MariaDbSettings(
                        md.getString("host", "127.0.0.1"),
                        md.getInt("port", 3306),
                        md.getString("database", "authsecurity"),
                        md.getString("username", "auth"),
                        md.getString("password", ""),
                        md.getString("parameters", "")
                ),
                new PluginConfig.PoolSettings(
                        pool.getInt("maximum-pool-size", 8),
                        pool.getInt("minimum-idle", 2),
                        pool.getInt("connection-timeout-millis", 5000)
                )
        );
    }

    private static PluginConfig.SecurityConfig readSecurity(ConfigurationSection s) {
        long ttlMinutes = s.contains("session-ttl-minutes")
                ? s.getLong("session-ttl-minutes", 60L)
                : s.getLong("session-ttl-hours", 1L) * 60L;

        ConfigurationSection lockoutSec = s.getConfigurationSection("lockout");
        PluginConfig.LockoutConfig lockout = lockoutSec == null
                ? new PluginConfig.LockoutConfig(false, 5, 15L)
                : new PluginConfig.LockoutConfig(
                        lockoutSec.getBoolean("enabled", false),
                        lockoutSec.getInt("max-attempts", 5),
                        lockoutSec.getLong("ban-minutes", 15L));

        ConfigurationSection idleSec = s.getConfigurationSection("idle-logout");
        PluginConfig.IdleLogoutConfig idle = idleSec == null
                ? new PluginConfig.IdleLogoutConfig(false, 30L)
                : new PluginConfig.IdleLogoutConfig(
                        idleSec.getBoolean("enabled", false),
                        idleSec.getLong("minutes", 30L));

        ConfigurationSection policySec = s.getConfigurationSection("password-policy");
        PluginConfig.PasswordPolicyConfig policy = policySec == null
                ? new PluginConfig.PasswordPolicyConfig(false)
                : new PluginConfig.PasswordPolicyConfig(
                        policySec.getBoolean("require-letter-and-digit", false));

        return new PluginConfig.SecurityConfig(
                s.getInt("max-attempts", 3),
                ttlMinutes,
                s.getLong("login-timeout-minutes", 3L),
                s.getInt("password-min-length", 6),
                s.getInt("password-max-length", 72),
                s.getInt("accounts-per-ip-limit", 3),
                s.getInt("max-concurrent-auth-sessions", 100),
                lockout,
                idle,
                policy
        );
    }

    private static PluginConfig.SupportConfig readSupport(ConfigurationSection s) {
        return new PluginConfig.SupportConfig(s.getString("discord-url", ""));
    }

    private static PluginConfig.CaptchaConfig readCaptcha(ConfigurationSection s) {
        if (s == null) {
            return new PluginConfig.CaptchaConfig(
                    false, "", "", "0.0.0.0", 25590, "",
                    10, 7, false, true, 50, defaultWebTexts());
        }
        return new PluginConfig.CaptchaConfig(
                s.getBoolean("enabled", false),
                s.getString("site-key", ""),
                s.getString("secret-key", ""),
                s.getString("web-bind", "0.0.0.0"),
                s.getInt("web-port", 25590),
                s.getString("public-base-url", ""),
                s.getInt("token-ttl-minutes", 10),
                s.getInt("verification-validity-days", 7),
                s.getBoolean("refresh-verification-on-login", false),
                s.getBoolean("revalidate-on-ip-change", true),
                s.getInt("max-concurrent-challenges", 50),
                readWebTexts(s.getConfigurationSection("web-texts"))
        );
    }

    private static PluginConfig.CaptchaWebTexts defaultWebTexts() {
        return new PluginConfig.CaptchaWebTexts(
                "ru",
                "PinkyFoxy — Проверка от ботов",
                "PINKY FOXY",
                "~ Проверка от ботов ~",
                "Подтвердите, что вы не бот",
                "Пройдите проверку ниже, чтобы войти на сервер.\nПосле успешной проверки вкладку можно закрыть — Minecraft продолжит вход автоматически.",
                "Если виджет не загрузился, проверьте, что включен JavaScript и доступен Cloudflare.",
                "© PinkyFoxy · Проверка Cloudflare Turnstile",
                "Проверяем...",
                "ПРОВЕРКА ПРОЙДЕНА!\nВкладку можно закрыть и вернуться в Minecraft.\nОкно входа появится автоматически.",
                "Проверка не пройдена.\nПопробуйте еще раз.",
                "Ошибка сети.\nПроверьте подключение и попробуйте снова.",
                "Ошибка виджета captcha.\nОбновите страницу."
        );
    }

    private static PluginConfig.CaptchaWebTexts readWebTexts(ConfigurationSection s) {
        PluginConfig.CaptchaWebTexts d = defaultWebTexts();
        if (s == null) return d;
        return new PluginConfig.CaptchaWebTexts(
                s.getString("lang", d.lang()),
                s.getString("title", d.title()),
                s.getString("brand", d.brand()),
                s.getString("tagline", d.tagline()),
                s.getString("heading", d.heading()),
                s.getString("intro", d.intro()),
                s.getString("hint", d.hint()),
                s.getString("footer", d.footer()),
                s.getString("status-verifying", d.statusVerifying()),
                s.getString("status-verified", d.statusVerified()),
                s.getString("status-failed", d.statusFailed()),
                s.getString("status-network", d.statusNetwork()),
                s.getString("status-widget-error", d.statusWidgetError())
        );
    }

    private static Messages readMessages(ConfigurationSection s) {
        return new Messages(
                Messages.parse(s.getString("login-title", "")),
                s.getString("login-welcome", ""),
                Messages.parse(s.getString("login-button", "")),
                Messages.parse(s.getString("forgot-password-button", "")),
                Messages.parse(s.getString("register-title", "")),
                s.getString("register-welcome", ""),
                Messages.parse(s.getString("register-button", "")),
                Messages.parse(s.getString("cancel-button", "")),
                Messages.parse(s.getString("password-label", "")),
                Messages.parse(s.getString("password-confirm-label", "")),
                s.getString("wrong-password", ""),
                Messages.parse(s.getString("too-many-attempts", "")),
                Messages.parse(s.getString("login-cancelled", "")),
                Messages.parse(s.getString("login-timed-out", "")),
                Messages.parse(s.getString("internal-error", "")),
                Messages.parse(s.getString("password-empty", "")),
                s.getString("password-too-short", ""),
                s.getString("password-too-long", ""),
                Messages.parse(s.getString("passwords-mismatch", "")),
                Messages.parse(s.getString("uuid-missing", "")),
                s.getString("ip-limit-reached", ""),
                s.getString("account-locked", ""),
                s.getString("wrong-username-case", ""),
                s.getString("name-already-registered", ""),
                Messages.parse(s.getString("idle-kick", "")),
                Messages.parse(s.getString("password-requires-alphanumeric", "")),
                Messages.parse(s.getString("forgot-password-title", "")),
                Messages.parse(s.getString("forgot-password-body", "")),
                Messages.parse(s.getString("forgot-password-discord-button", "")),
                Messages.parse(s.getString("forgot-password-back-button", "")),
                Messages.parse(s.getString("change-password-title", "")),
                Messages.parse(s.getString("change-password-body", "")),
                Messages.parse(s.getString("change-password-old-label", "")),
                Messages.parse(s.getString("change-password-new-label", "")),
                Messages.parse(s.getString("change-password-confirm-label", "")),
                Messages.parse(s.getString("change-password-button", "")),
                Messages.parse(s.getString("change-password-success", "")),
                Messages.parse(s.getString("change-password-wrong-old", "")),
                Messages.parse(s.getString("command-only-players", "")),
                Messages.parse(s.getString("command-not-authenticated", "")),
                s.getString("command-player-not-found", ""),
                s.getString("command-unregister-success", ""),
                s.getString("command-changepassword-admin-success", ""),
                s.getString("command-accountinfo-header", ""),
                s.getString("command-accountinfo-line", ""),
                Messages.parse(s.getString("command-no-permission", "")),
                s.getString("command-logout-success", ""),
                s.getString("command-logout-not-online", ""),
                Messages.parse(s.getString("command-logout-kick", "")),
                Messages.parse(s.getString("command-trustip-enabled", "")),
                Messages.parse(s.getString("command-trustip-disabled", "")),
                Messages.parse(s.getString("command-trustip-unavailable", "")),
                Messages.parse(s.getString("trusted-ip-login-hint", "")),
                Messages.parse(s.getString("admin-password-changed-kick", "")),
                Messages.parse(s.getString("admin-account-unregistered-kick", "")),
                Messages.parse(s.getString("command-reload-started", "")),
                Messages.parse(s.getString("command-reload-success", "")),
                Messages.parse(s.getString("command-reload-failed", "")),

                Messages.parse(s.getString("captcha-welcome-title", "")),
                Messages.parse(s.getString("captcha-renewal-title", "")),
                s.getString("captcha-welcome-body", ""),
                s.getString("captcha-renewal-body", ""),
                Messages.parse(s.getString("captcha-button-open", "")),
                Messages.parse(s.getString("captcha-button-discord", "")),
                Messages.parse(s.getString("captcha-button-disconnect", "")),
                Messages.parse(s.getString("captcha-issue-error", "")),
                Messages.parse(s.getString("captcha-server-busy", "")),
                Messages.parse(s.getString("auth-server-busy", ""))
        );
    }

    private static void validate(PluginConfig c) {
        List<String> errors = new ArrayList<>();

        PluginConfig.DatabaseConfig db = c.database();
        String type = db.type() == null ? "" : db.type();
        if (!"h2".equalsIgnoreCase(type) && !"mariadb".equalsIgnoreCase(type)) {
            errors.add("database.type must be either 'h2' or 'mariadb'");
        }
        if (db.pool().maximumPoolSize() < 1) errors.add("database.pool.maximum-pool-size must be at least 1");
        if (db.pool().minimumIdle() < 0) errors.add("database.pool.minimum-idle must be at least 0");
        if (db.pool().minimumIdle() > db.pool().maximumPoolSize()) {
            errors.add("database.pool.minimum-idle must not exceed database.pool.maximum-pool-size");
        }
        if (db.pool().connectionTimeoutMillis() < 1000) {
            errors.add("database.pool.connection-timeout-millis must be at least 1000");
        }

        PluginConfig.SecurityConfig sec = c.security();
        if (sec.maxAttempts() < 1) errors.add("security.max-attempts must be at least 1");
        if (sec.sessionTtlMinutes() < 0) errors.add("security.session-ttl-minutes must be 0 or greater");
        if (sec.loginTimeoutMinutes() < 1) errors.add("security.login-timeout-minutes must be at least 1");
        if (sec.passwordMinLength() < 1) errors.add("security.password-min-length must be at least 1");
        if (sec.passwordMaxLength() < sec.passwordMinLength()) {
            errors.add("security.password-max-length must be greater than or equal to security.password-min-length");
        }
        if (sec.accountsPerIpLimit() < 1) errors.add("security.accounts-per-ip-limit must be at least 1");
        if (sec.maxConcurrentAuthSessions() < 0) {
            errors.add("security.max-concurrent-auth-sessions must be 0 or greater");
        }
        if (sec.lockout().maxAttempts() < 1) errors.add("security.lockout.max-attempts must be at least 1");
        if (sec.lockout().banMinutes() < 1) errors.add("security.lockout.ban-minutes must be at least 1");
        if (sec.idleLogout().minutes() < 1) errors.add("security.idle-logout.minutes must be at least 1");

        PluginConfig.CaptchaConfig captcha = c.captcha();
        if (captcha.webPort() < 1 || captcha.webPort() > 65535) {
            errors.add("captcha.web-port must be between 1 and 65535");
        }
        if (captcha.tokenTtlMinutes() < 1) errors.add("captcha.token-ttl-minutes must be at least 1");
        if (captcha.verificationValidityDays() < 0) {
            errors.add("captcha.verification-validity-days must be 0 or greater");
        }
        if (captcha.maxConcurrentChallenges() < 0) {
            errors.add("captcha.max-concurrent-challenges must be 0 or greater");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid config.yml:\n - " + String.join("\n - ", errors));
        }
    }
}
