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

/**
 * Copies the bundled default config on first run and parses it into a {@link PluginConfig}.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    public static PluginConfig load(Plugin plugin) {
        plugin.getDataFolder().mkdirs();
        File target = new File(plugin.getDataFolder(), "config.yml");
        if (!target.exists()) {
            try (InputStream in = plugin.getResource("config.yml")) {
                if (in == null) throw new IllegalStateException("config.yml missing from plugin jar");
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write default config.yml", e);
            }
        }

        FileConfiguration yml = YamlConfiguration.loadConfiguration(target);
        FileConfiguration defaults = loadBundledDefaults(plugin);
        if (defaults != null) yml.setDefaults(defaults);

        return new PluginConfig(
                readDatabase(section(yml, "database")),
                readSecurity(section(yml, "security")),
                readSupport(section(yml, "support")),
                readMessages(section(yml, "messages"))
        );
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
                ? new PluginConfig.LockoutConfig(true, 5, 15L)
                : new PluginConfig.LockoutConfig(
                        lockoutSec.getBoolean("enabled", true),
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
                s.getInt("max-attempts", 5),
                ttlMinutes,
                s.getLong("login-timeout-minutes", 3L),
                s.getInt("password-min-length", 6),
                s.getInt("password-max-length", 72),
                s.getInt("accounts-per-ip-limit", 3),
                lockout,
                idle,
                policy
        );
    }

    private static PluginConfig.SupportConfig readSupport(ConfigurationSection s) {
        return new PluginConfig.SupportConfig(s.getString("discord-url", ""));
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
                Messages.parse(s.getString("command-reload-started", "")),
                Messages.parse(s.getString("command-reload-success", "")),
                Messages.parse(s.getString("command-reload-failed", ""))
        );
    }
}
