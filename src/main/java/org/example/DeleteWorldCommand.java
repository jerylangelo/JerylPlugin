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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DeleteWorldCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /deleteworld <worldname>");
            return true;
        }

        String worldName = args[0];
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            player.sendMessage(ChatColor.RED + "World '" + worldName + "' is not loaded or doesn't exist!");
            return true;
        }

        // Prevent deleting the default world
        World defaultWorld = Bukkit.getWorlds().get(0);
        if (world.equals(defaultWorld)) {
            player.sendMessage(ChatColor.RED + "You cannot delete the default world!");
            return true;
        }

        // Teleport all players in the target world to the default world's spawn
        for (Player p : world.getPlayers()) {
            p.teleport(defaultWorld.getSpawnLocation());
            p.sendMessage(ChatColor.YELLOW + "World '" + worldName + "' is being deleted. You have been relocated.");
        }

        // Grab the world folder before unloading
        File worldFolder = world.getWorldFolder();

        // Unload without saving (it's being deleted)
        boolean unloaded = Bukkit.unloadWorld(world, false);
        if (!unloaded) {
            player.sendMessage(ChatColor.RED + "Failed to unload world '" + worldName + "'.");
            return true;
        }

        // Recursively delete the world folder
        deleteDirectory(worldFolder);

        player.sendMessage(ChatColor.GREEN + "World '" + ChatColor.YELLOW + worldName
                + ChatColor.GREEN + "' has been deleted.");
        return true;
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            // Suggest all loaded worlds except the default world
            World defaultWorld = Bukkit.getWorlds().get(0);
            List<String> worldNames = Bukkit.getWorlds().stream()
                    .filter(w -> !w.equals(defaultWorld))
                    .map(World::getName)
                    .collect(Collectors.toList());
            return StringUtil.copyPartialMatches(args[0], worldNames, completions);
        }
        return completions;
    }
}
