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
            sender.sendMessage(ChatColor.YELLOW + "• Use a wind charge to unleash lightning, instant kill mobs and clear items.");
            sender.sendMessage(ChatColor.YELLOW + "• /CocoPops.");
            sender.sendMessage(ChatColor.YELLOW + "• /Airstrike");
            sender.sendMessage(ChatColor.YELLOW + "• /huntingrifle");
            sender.sendMessage(ChatColor.YELLOW + "• Pilson Poon");
            sender.sendMessage(ChatColor.GOLD + "========================");

            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown argument. Use /jerylplugin help");
        return true;
    }
}