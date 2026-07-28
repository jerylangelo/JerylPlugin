package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

public class SmiteCommand implements CommandExecutor {

    private final Plugin plugin;

    public SmiteCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // Usage check
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /smite <player>");
            return true;
        }

        // Find target player
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online!");
            return true;
        }

        Location targetLoc = target.getLocation();
        World world = targetLoc.getWorld();

        if (world == null) return true;

        // Broadcast global message to all online players
        Bukkit.broadcastMessage(ChatColor.YELLOW + target.getName() + ChatColor.RED + " has been a naughty boy!");
        // Strike lightning multiple times (5 strikes, 2 ticks apart = 0.5s duration)
        new BukkitRunnable() {
            int strikesLeft = 5;

            @Override
            public void run() {
                if (strikesLeft <= 0 || !target.isOnline()) {
                    // Instantly kill target player after lightning sequence
                    target.setHealth(0.0);
                    this.cancel();
                    return;
                }

                // Strike lightning at player's current location
                world.strikeLightning(target.getLocation());
                strikesLeft--;
            }
        }.runTaskTimer(plugin, 0L, 1L); // 0 tick delay, repeats every 2 ticks (0.1 sec)

        return true;
    }
}