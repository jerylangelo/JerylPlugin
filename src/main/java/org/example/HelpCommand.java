package org.example;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class HelpCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // Handle "/jerylplugin" or "/jerylplugin help" or "/help JerylPlugin"
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {

            sender.sendMessage(ChatColor.GOLD + "=== JerylPlugin Help ===");
            sender.sendMessage(ChatColor.YELLOW
                    + "* Wind Charge: throw one to unleash lightning, instantly kill mobs and clear dropped items.");
            sender.sendMessage(ChatColor.YELLOW
                    + "* /cocopops: get a CocoPops cluster bomb - right-click to launch, it lands then splits into exploding cocoa beans.");
            sender.sendMessage(ChatColor.YELLOW
                    + "* /airstrike: get a signal flare - right-click to throw it; where it lands, a fence bombardment rains down.");
            sender.sendMessage(ChatColor.YELLOW
                    + "* /huntingrifle: get a sniper rifle - right-click to toggle FOV zoom, left-click to fire (headshots are lethal).");
            sender.sendMessage(ChatColor.YELLOW
                    + "* /meteorite <size> [NO_ROLLBACK]: get a meteorite (size 1-500). Right-click a block to strike it.");
            sender.sendMessage(ChatColor.GRAY
                    + "    Craters bigger than size 5 auto-heal after a delay. Add NO_ROLLBACK to make the damage permanent.");
            sender.sendMessage(ChatColor.YELLOW + "* /smite <player>: smite a naughty player.");
            sender.sendMessage(ChatColor.GOLD + "========================");

            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown argument. Use /jerylplugin help");
        return true;
    }
}