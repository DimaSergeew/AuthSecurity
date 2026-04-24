package me.bedepay.authsecurity.auth;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.bedepay.authsecurity.config.Messages;
import me.bedepay.authsecurity.config.PluginConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Tracks player activity and kicks players who stayed idle past the configured window.
 * Activity is refreshed on move/interact/chat/command. The sweeper runs once a minute
 * on the async scheduler.
 */
@SuppressWarnings("UnstableApiUsage")
public final class IdleWatcher implements Listener {

    private final Plugin plugin;
    private final ConcurrentHashMap<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    private volatile PluginConfig.IdleLogoutConfig config;
    private volatile Messages messages;
    private volatile ScheduledTask sweeperTask;

    public IdleWatcher(Plugin plugin, PluginConfig.IdleLogoutConfig config, Messages messages) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
    }

    public void start() {
        stop();
        if (!config.enabled() || config.minutes() <= 0) return;
        sweeperTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin, $ -> sweep(), 30, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        if (sweeperTask != null) {
            sweeperTask.cancel();
            sweeperTask = null;
        }
    }

    public void applyConfig(PluginConfig.IdleLogoutConfig config, Messages messages) {
        this.config = config;
        this.messages = messages;
        start();
    }

    public void touch(UUID uuid) {
        lastActivity.put(uuid, System.currentTimeMillis());
    }

    private void sweep() {
        if (!config.enabled() || config.minutes() <= 0) return;
        long thresholdMs = TimeUnit.MINUTES.toMillis(config.minutes());
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Long> entry : lastActivity.entrySet()) {
            if (now - entry.getValue() < thresholdMs) continue;
            UUID uuid = entry.getKey();
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                lastActivity.remove(uuid);
                continue;
            }
            player.getScheduler().run(plugin, $ -> player.kick(messages.idleKick()), null);
        }
    }

    // =========================================================================
    // Activity sources
    // =========================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        touch(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        touch(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        touch(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        touch(event.getPlayer().getUniqueId());
    }
}
