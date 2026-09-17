package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class DoomWorldCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;

    public DoomWorldCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        World world = player.getWorld();

        if (args.length >= 1 && args[0].equalsIgnoreCase("responsible")) {
            executeResponsible(player, world);
        } else {
            executeUnregulated(player, world);
        }

        return true;
    }

    /**
     * Unregulated mode: Spawn an absurd amount of TNTPrimed entities across every
     * loaded chunk. No throttling, no mercy. The server will almost certainly crash.
     */
    private void executeUnregulated(Player player, World world) {
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "⚠ DOOM HAS COME ⚠");
        Bukkit.broadcastMessage(ChatColor.RED + player.getName() + " has unleashed unregulated destruction upon "
                + ChatColor.GOLD + world.getName() + ChatColor.RED + "!");
        Bukkit.broadcastMessage(ChatColor.GRAY + "May God have mercy on your server.");

        Chunk[] loadedChunks = world.getLoadedChunks();
        int tntCount = 0;

        for (Chunk chunk : loadedChunks) {
            int baseX = chunk.getX() << 4; // chunk coord * 16
            int baseZ = chunk.getZ() << 4;

            // Spawn one TNT per column at the surface — skip all-air columns
            for (int x = baseX; x < baseX + 16; x += 4) {
                for (int z = baseZ; z < baseZ + 16; z += 4) {
                    int highestY = world.getHighestBlockYAt(x, z);
                    if (highestY <= world.getMinHeight()) continue; // all air, skip

                    Location loc = new Location(world, x + 0.5, highestY + 1.0, z + 0.5);
                    TNTPrimed tnt = (TNTPrimed) world.spawnEntity(loc, EntityType.TNT);
                    tnt.setFuseTicks(20 + (tntCount % 60)); // stagger fuses for rolling carnage
                    tntCount++;
                }
            }
        }

        Bukkit.broadcastMessage(ChatColor.DARK_RED + "Spawned " + ChatColor.YELLOW + String.format("%,d", tntCount)
                + ChatColor.DARK_RED + " TNT across " + ChatColor.YELLOW + loadedChunks.length
                + ChatColor.DARK_RED + " chunks. Good luck.");
    }

    /**
     * Responsible mode: Tick-sliced batching using instant explosions instead of
     * TNTPrimed entities. Keeps the server at ~20 TPS by budgeting 45ms per tick
     * for explosions, leaving 5ms headroom for the rest of the server.
     */
    private void executeResponsible(Player player, World world) {
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "☢ RESPONSIBLE DOOM PROTOCOL INITIATED ☢");
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + player.getName()
                + " has activated the responsible doomsday device in "
                + ChatColor.GOLD + world.getName() + ChatColor.LIGHT_PURPLE + ".");
        Bukkit.broadcastMessage(ChatColor.GRAY + "The world will be surgically dismantled.");

        // Step 1: Disable drops to reduce entity overhead
        world.setGameRule(GameRule.BLOCK_DROPS, false);
        world.setGameRule(GameRule.ENTITY_DROPS, false);

        // Step 2: Build the explosion queue — one explosion per column at the surface
        Chunk[] loadedChunks = world.getLoadedChunks();
        Queue<Location> locationQueue = new LinkedList<>();

        for (Chunk chunk : loadedChunks) {
            int baseX = chunk.getX() << 4;
            int baseZ = chunk.getZ() << 4;

            for (int x = baseX; x < baseX + 16; x += 5) {
                for (int z = baseZ; z < baseZ + 16; z += 5) {
                    int highestY = world.getHighestBlockYAt(x, z);
                    if (highestY <= world.getMinHeight()) continue; // all air, skip

                    locationQueue.add(new Location(world, x + 0.5, highestY + 0.5, z + 0.5));
                }
            }
        }

        final int totalExplosions = locationQueue.size();

        Bukkit.broadcastMessage(ChatColor.GOLD + "Queued " + ChatColor.YELLOW + String.format("%,d", totalExplosions)
                + ChatColor.GOLD + " explosions across " + ChatColor.YELLOW + loadedChunks.length
                + ChatColor.GOLD + " chunks.");

        // Step 3: Tick-sliced BukkitRunnable — 45ms budget per tick
        new BukkitRunnable() {
            private int totalProcessed = 0;
            private int tickCount = 0;
            private static final long TICK_BUDGET_MS = 45; // leave 5ms for the rest of the server
            private static final int PROGRESS_INTERVAL_TICKS = 100; // broadcast progress every 5 seconds

            @Override
            public void run() {
                long startTime = System.currentTimeMillis();
                int processedThisTick = 0;

                // Keep exploding until we hit the time budget or run out of targets
                while (System.currentTimeMillis() - startTime < TICK_BUDGET_MS) {
                    if (locationQueue.isEmpty()) {
                        // All done — clean up and announce
                        Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "✓ RESPONSIBLE DOOM COMPLETE");
                        Bukkit.broadcastMessage(ChatColor.GREEN + "Detonated " + ChatColor.YELLOW
                                + String.format("%,d", totalProcessed) + ChatColor.GREEN
                                + " explosions in " + ChatColor.YELLOW + tickCount + ChatColor.GREEN
                                + " ticks (" + String.format("%.1f", tickCount / 20.0) + "s).");

                        // Restore game rules
                        world.setGameRule(GameRule.BLOCK_DROPS, true);
                        world.setGameRule(GameRule.ENTITY_DROPS, true);

                        this.cancel();
                        return;
                    }

                    Location loc = locationQueue.poll();
                    // createExplosion(Location, power, setFire, breakBlocks)
                    // breakBlocks=true so it actually destroys terrain, no item drops via game rule
                    loc.getWorld().createExplosion(loc, 4.0F, true, true);
                    processedThisTick++;
                }

                totalProcessed += processedThisTick;
                tickCount++;

                // Periodic progress broadcast
                if (tickCount % PROGRESS_INTERVAL_TICKS == 0) {
                    double percent = (totalProcessed / (double) totalExplosions) * 100.0;
                    int remaining = locationQueue.size();
                    Bukkit.broadcastMessage(ChatColor.GOLD + "☢ Doom Progress: "
                            + ChatColor.YELLOW + String.format("%.1f%%", percent)
                            + ChatColor.GOLD + " | " + ChatColor.YELLOW + String.format("%,d", remaining)
                            + ChatColor.GOLD + " explosions remaining"
                            + ChatColor.GRAY + " (" + processedThisTick + " this tick)");
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], List.of("responsible"), completions);
        }
        return completions;
    }
}
