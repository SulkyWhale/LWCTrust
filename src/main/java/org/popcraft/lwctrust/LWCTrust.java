package org.popcraft.lwctrust;

import com.griefcraft.lwc.LWC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.popcraft.lwctrust.locale.FileResourceLoader;
import org.popcraft.lwctrust.locale.UTF8Control;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class LWCTrust extends JavaPlugin {

    private ResourceBundle defaultBundle, localeBundle;
    private TrustCache trustCache, confirmCache;
    private Metrics metrics;

    @Override
    public void onEnable() {
        // Copy any missing configuration options
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();
        // Make the trusts directory if it doesn't already exist
        File trustDirectory = new File(this.getDataFolder() + File.separator + "trusts");
        if (!trustDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            trustDirectory.mkdir();
        }
        // Load locales
        String locale = this.getConfig().getString("locale", "en");
        File messageFile = new File(this.getDataFolder() + File.separator +
                "locale_" + locale + ".properties");
        InputStream localeResource = this.getResource("locale_" + locale + ".properties");
        // Default locale is English
        this.defaultBundle = ResourceBundle.getBundle("locale", Locale.ENGLISH, new UTF8Control());
        if (messageFile.exists()) {
            // Load a custom provided locale file from the plugin folder
            this.localeBundle = ResourceBundle.getBundle("locale", Locale.of(locale),
                    new FileResourceLoader(this), new UTF8Control());
        } else if (localeResource != null) {
            // Load another valid locale that is included with the plugin
            this.localeBundle = ResourceBundle.getBundle("locale", Locale.of(locale),
                    new UTF8Control());
        } else {
            // Fall back to the default locale
            this.localeBundle = this.defaultBundle;
        }
        // Set up caches used by the plugin
        int cacheSize = this.getConfig().getInt("cache-size", 1000);
        this.trustCache = new TrustCache(this, cacheSize);
        this.confirmCache = new TrustCache(this, cacheSize);
        // Hook into LWC
        try {
            LWC.getInstance().getModuleLoader().registerModule(this, new TrustModule(this));
        } catch (NoClassDefFoundError e) {
            this.getLogger().severe(getMessage("error.nolwc"));
            this.getLogger().severe(getMessage("url.lwc"));
            getServer().getPluginManager().disablePlugin(this);
        }
        // Enable bStats metrics
        int pluginId = 6614;
        this.metrics = new Metrics(this, pluginId);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Only players can trust
        if (args.length < 1 || !(sender instanceof Player player)) {
            sender.sendMessage(getMessage("trust.description"));
            return false;
        }
        boolean confirm = this.getConfig().getBoolean("confirm-action", true);
        UUID playerUniqueId = player.getUniqueId();
        if ("add".equalsIgnoreCase(args[0]) && player.hasPermission("lwctrust.trust.add")) {
            // Get a list of existing unique players to add from the arguments
            List<UUID> toTrust = new ArrayList<>();
            Arrays.stream(args).forEach(a -> {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(a);
                if (offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline()
                        && !toTrust.contains(offlinePlayer.getUniqueId())) {
                    toTrust.add(offlinePlayer.getUniqueId());
                }
            });
            if (confirm) {
                // If we need to confirm, just add these to the confirmation cache
                confirmCache.put(playerUniqueId, toTrust);
                player.sendMessage(getMessage("trust.add.confirm"));
            } else {
                // Otherwise we are just going to directly save any new players to the player's trusts
                List<UUID> trusted = trustCache.load(playerUniqueId);
                toTrust.forEach(uuid -> {
                    if (!trusted.contains(uuid)) {
                        trusted.add(uuid);
                    }
                    player.sendMessage(getMessage("trust.add", Bukkit.getOfflinePlayer(uuid).getName()));
                    Player onlinePlayer = Bukkit.getPlayer(uuid);
                    if (onlinePlayer != null) {
                        onlinePlayer.sendMessage(getMessage("trust.add.notify", player.getName()));
                    }
                });
                trustCache.save(playerUniqueId);
            }
        } else if ("remove".equalsIgnoreCase(args[0]) && player.hasPermission("lwctrust.trust.remove")) {
            // Load a player's trusts, remove any players matching the arguments, and save
            List<UUID> trusted = trustCache.load(playerUniqueId);
            Arrays.stream(args, 1, args.length).forEach(a -> {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(a);
                trusted.remove(offlinePlayer.getUniqueId());
                player.sendMessage(getMessage("trust.remove", offlinePlayer.getName()));
                Player onlinePlayer = offlinePlayer.getPlayer();
                if (onlinePlayer != null) {
                    onlinePlayer.sendMessage(getMessage("trust.remove.notify", player.getName()));
                }
            });
            trustCache.save(playerUniqueId);
        } else if ("list".equalsIgnoreCase(args[0]) && player.hasPermission("lwctrust.trust.list")) {
            UUID targetUniqueId = args.length > 1 && player.hasPermission("lwctrust.trust.list.others")
                    ? Bukkit.getOfflinePlayer(args[1]).getUniqueId()
                    : playerUniqueId;
            boolean isSelf = playerUniqueId.equals(targetUniqueId);
            // Load a player's trusts and send them a list
            List<UUID> trusted = trustCache.load(targetUniqueId);
            if (trusted.isEmpty()) {
                player.sendMessage(isSelf
                        ? getMessage("trust.list.empty")
                        : getMessage("trust.list.empty.other", Bukkit.getOfflinePlayer(targetUniqueId).getName()));
            } else {
                List<String> playerNames = trusted.stream()
                        .map(uuid -> Bukkit.getOfflinePlayer(uuid).getName()).collect(Collectors.toList());
                player.sendMessage(isSelf
                        ? getMessage("trust.list", String.join(", ", playerNames))
                        : getMessage("trust.list.other", Bukkit.getOfflinePlayer(targetUniqueId).getName(), String.join(", ", playerNames)));
            }
        } else if ("confirm".equalsIgnoreCase(args[0])) {
            // Add any trusts from pending confirmations, if any
            if (confirmCache.containsKey(playerUniqueId)) {
                List<UUID> trusted = trustCache.load(playerUniqueId);
                confirmCache.get(playerUniqueId).forEach(uuid -> {
                    if (!trusted.contains(uuid)) {
                        trusted.add(uuid);
                    }
                    player.sendMessage(getMessage("trust.add", Bukkit.getOfflinePlayer(uuid).getName()));
                    Player onlinePlayer = Bukkit.getPlayer(uuid);
                    if (onlinePlayer != null) {
                        onlinePlayer.sendMessage(getMessage("trust.add.notify", player.getName()));
                    }
                });
                confirmCache.remove(playerUniqueId);
                trustCache.save(playerUniqueId);
            } else {
                player.sendMessage(getMessage("trust.confirm.empty"));
            }
        } else if ("cancel".equalsIgnoreCase(args[0])) {
            // Cancel pending trust confirmations, if any
            if (confirmCache.containsKey(playerUniqueId)) {
                confirmCache.remove(playerUniqueId);
                player.sendMessage(getMessage("trust.cancel"));
            } else {
                player.sendMessage(getMessage("trust.confirm.empty"));
            }
        } else {
            sender.sendMessage(getMessage("trust.description"));
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (player.hasPermission("lwctrust.trust.add")) {
                completions.add("add");
            }
            if (player.hasPermission("lwctrust.trust.remove")) {
                completions.add("remove");
            }
            if (player.hasPermission("lwctrust.trust.list")) {
                completions.add("list");
            }
            if (confirmCache.containsKey(player.getUniqueId())) {
                completions.addAll(Arrays.asList("confirm", "cancel"));
            }
            return completions.stream().filter(s -> s.startsWith(args[0])).collect(Collectors.toList());
        } else if (args.length > 1 && Arrays.asList("add", "remove", "list").contains(args[0])) {
            return null;
        } else {
            return Collections.emptyList();
        }
    }

    public static final Pattern RGB_PATTERN = Pattern.compile("&#[0-9a-fA-F]{6}");

    public String getMessage(String key, Object... args) {
        String localMessage = localeBundle.containsKey(key)
                ? localeBundle.getString(key) : defaultBundle.getString(key);
        String formattedMessage = String.format(localMessage, args);
        Matcher rgbMatcher = RGB_PATTERN.matcher(formattedMessage);
        while (rgbMatcher.find()) {
            String rgbMatch = rgbMatcher.group();
            String rgbColor = String.valueOf(ChatColor.COLOR_CHAR) + 'x' +
                    ChatColor.COLOR_CHAR + rgbMatch.charAt(2) +
                    ChatColor.COLOR_CHAR + rgbMatch.charAt(3) +
                    ChatColor.COLOR_CHAR + rgbMatch.charAt(4) +
                    ChatColor.COLOR_CHAR + rgbMatch.charAt(5) +
                    ChatColor.COLOR_CHAR + rgbMatch.charAt(6) +
                    ChatColor.COLOR_CHAR + rgbMatch.charAt(7);
            formattedMessage = formattedMessage.replaceAll(rgbMatch, rgbColor);
        }
        return ChatColor.translateAlternateColorCodes('&', formattedMessage);
    }

    public TrustCache getTrustCache() {
        return trustCache;
    }

}
