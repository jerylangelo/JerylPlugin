package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class NewWorldCommand implements CommandExecutor, TabCompleter {

    private static final List<String> WORLD_TYPES = List.of("overworld", "nether", "end");

    private final Plugin plugin;

    public NewWorldCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /newworld <worldname> [overworld|nether|end]");
            return true;
        }

        String worldName = args[0];

        // Check if a world with this name already exists
        if (Bukkit.getWorld(worldName) != null) {
            player.sendMessage(ChatColor.RED + "A world named '" + worldName + "' is already loaded!");
            return true;
        }

        // Parse the world type (default: overworld)
        String typeArg = (args.length >= 2) ? args[1].toLowerCase() : "overworld";
        World.Environment environment;

        switch (typeArg) {
            case "overworld":
                environment = World.Environment.NORMAL;
                break;
            case "nether":
                environment = World.Environment.NETHER;
                break;
            case "end":
                environment = World.Environment.THE_END;
                break;
            default:
                player.sendMessage(ChatColor.RED + "Unknown world type '" + args[1]
                        + "'. Valid options: overworld, nether, end");
                return true;
        }

        player.sendMessage(ChatColor.GRAY + "Creating world '" + worldName + "' (" + typeArg + ")...");

        WorldCreator creator = new WorldCreator(worldName);
        creator.environment(environment);

        World newWorld = Bukkit.createWorld(creator);

        if (newWorld != null) {
            // Persist this world to config so it auto-loads on server restart
            List<String> worlds = plugin.getConfig().getStringList("worlds");
            if (!worlds.contains(worldName)) {
                worlds.add(worldName);
                plugin.getConfig().set("worlds", worlds);
                plugin.saveConfig();
            }

            player.sendMessage(ChatColor.GREEN + "World '" + ChatColor.YELLOW + worldName
                    + ChatColor.GREEN + "' created successfully! ("
                    + ChatColor.GOLD + typeArg + ChatColor.GREEN + ")");
            player.sendMessage(ChatColor.GRAY + "Teleporting you there...");
            player.teleport(newWorld.getSpawnLocation());
        } else {
            player.sendMessage(ChatColor.RED + "Failed to create world '" + worldName + "'.");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], WORLD_TYPES, completions);
        }
        return completions;
    }
}
