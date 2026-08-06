package org.example;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BoatStepListener implements Listener {

    private final Plugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public BoatStepListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // 1. Check if riding a boat
        if (!(player.getVehicle() instanceof Boat boat)) {
            return;
        }

        UUID boatId = boat.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // 2. Cooldown check (1 second)
        if (cooldowns.containsKey(boatId) && (currentTime - cooldowns.get(boatId)) < 1000) {
            return;
        }

        Location boatLoc = boat.getLocation();
        Vector direction = boatLoc.getDirection().setY(0).normalize();

        // 3. Look 0.8 blocks directly in front of the boat
        Location frontLoc = boatLoc.clone().add(direction.clone().multiply(0.8));
        Block frontBlock = frontLoc.getBlock();
        Block aboveFrontBlock = frontLoc.clone().add(0, 1, 0).getBlock();

        // 4. Check if hitting a 1-block wall with air above it
        if (frontBlock.getType().isSolid() && !aboveFrontBlock.getType().isSolid()) {

            cooldowns.put(boatId, currentTime);

            // Teleport destination (1.2 blocks up and 0.6 blocks forward)
            Location stepLoc = boatLoc.clone().add(0, 1.2, 0).add(direction.clone().multiply(0.6));
            stepLoc.setYaw(boatLoc.getYaw());
            stepLoc.setPitch(boatLoc.getPitch());

            List<Entity> passengers = new ArrayList<>(boat.getPassengers());
            boat.eject();

            if (boat.teleport(stepLoc)) {
                // Re-mount player after 3 ticks
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    for (Entity passenger : passengers) {
                        if (passenger.isValid() && boat.isValid()) {
                            boat.addPassenger(passenger);
                        }
                    }

                    // Multi-tick velocity task: Push boat for 6 ticks (~0.3s) to prevent client stopping
                    new BukkitRunnable() {
                        int ticksRun = 0;

                        @Override
                        public void run() {
                            if (ticksRun >= 6 || !boat.isValid()) {
                                this.cancel();
                                return;
                            }

                            // Keep pushing forward on every tick until client catches up
                            Vector forwardVelocity = direction.clone().multiply(0.45).setY(0.02);
                            boat.setVelocity(forwardVelocity);
                            ticksRun++;
                        }
                    }.runTaskTimer(plugin, 0L, 1L); // Run immediately, repeat every tick

                }, 3L);
            }
        }
    }
}