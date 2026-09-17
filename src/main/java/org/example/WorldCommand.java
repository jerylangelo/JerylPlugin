package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class WorldCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /world <worldname>");
            player.sendMessage(ChatColor.GRAY + "Loaded worlds: " + ChatColor.WHITE
                    + Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.joining(", ")));
            return true;
        }

        String worldName = args[0];
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            player.sendMessage(ChatColor.RED + "World '" + worldName + "' is not loaded or doesn't exist!");
            return true;
        }

        player.teleport(world.getSpawnLocation());
        player.sendMessage(ChatColor.GREEN + "Teleported to " + ChatColor.YELLOW + world.getName()
                + ChatColor.GREEN + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> worldNames = Bukkit.getWorlds().stream()
                    .map(World::getName)
                    .collect(Collectors.toList());
            return StringUtil.copyPartialMatches(args[0], worldNames, completions);
        }
        return completions;
    }
}
