package org.example;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class MeteoriteCommand implements CommandExecutor {

    private final Plugin plugin;

    public MeteoriteCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /meteorite <size> [NO_ROLLBACK]  "
                    + ChatColor.GRAY + "(size " + MeteoriteListener.MIN_SIZE + "-" + MeteoriteListener.MAX_SIZE + ")");
            return true;
        }

        int size;
        try {
            size = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Size must be a whole number between "
                    + MeteoriteListener.MIN_SIZE + " and " + MeteoriteListener.MAX_SIZE + ".");
            return true;
        }

        if (size < MeteoriteListener.MIN_SIZE || size > MeteoriteListener.MAX_SIZE) {
            player.sendMessage(ChatColor.RED + "Size must be between "
                    + MeteoriteListener.MIN_SIZE + " and " + MeteoriteListener.MAX_SIZE + ".");
            return true;
        }

        // Rollback is ON by default; the optional second arg "NO_ROLLBACK" disables it.
        boolean rollback = true;
        if (args.length >= 2) {
            if (args[1].equalsIgnoreCase("NO_ROLLBACK")) {
                rollback = false;
            } else {
                player.sendMessage(ChatColor.RED + "Unknown option '" + args[1]
                        + "'. Did you mean NO_ROLLBACK?");
                return true;
            }
        }

        player.getInventory().addItem(MeteoriteListener.createMeteoriteItem(plugin, size, rollback));
        player.sendMessage(ChatColor.GREEN + "Received Meteorite (size " + size + ")"
                + (rollback ? ChatColor.GRAY + " with auto-rollback." : ChatColor.GRAY + " (no rollback)."));
        return true;
    }
}
