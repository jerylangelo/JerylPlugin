package org.example;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

public class WindChargeListener implements Listener {
    private final Plugin plugin;
    private static final double RADIUS = 5.0;

    public WindChargeListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // Remove Wind Charge Cooldown --
    @EventHandler
    public void onWindChargeUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        // Check if the player right-clicked air or a block
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {

            // Check if they are holding a Wind Charge in either hand
            if (player.getInventory().getItemInMainHand().getType() == Material.WIND_CHARGE ||
                    player.getInventory().getItemInOffHand().getType() == Material.WIND_CHARGE) {

                // Instantly remove the cooldown (set to 0 ticks)
                player.setCooldown(Material.WIND_CHARGE, 0);
            }
        }
    }

    // Strike lightning, clear items
    @EventHandler
    public void onWindChargeHit(ProjectileHitEvent event) {
        // Check if the entity thrown is a Wind Charge
        if (event.getEntity() instanceof WindCharge windCharge) {

            // Get the exact location where the wind charge hit
            Location hitLocation = null;

            if (event.getHitEntity() != null) {
                hitLocation = event.getHitEntity().getLocation();
            } else if (event.getHitBlock() != null) {
                hitLocation = event.getHitBlock().getLocation();
            } else {
                hitLocation = windCharge.getLocation();
            }

            World world = hitLocation.getWorld();
            if (world == null)
                return;

            // Search for all entities within a 5-block radius of the impact
            for (Entity entity : world.getNearbyEntities(hitLocation, RADIUS, RADIUS, RADIUS)) {

                // 1. If it's a Living Mob (and NOT the player throwing it)
                if (entity instanceof LivingEntity livingEntity && !(entity instanceof Player)) {
                    // Strike lightning at the mob's position
                    world.strikeLightning(livingEntity.getLocation());

                    // Instantly kill the mob
                    livingEntity.setHealth(0.0);
                }

                // 2. If it's a Dropped Item entity, remove it completely from the world
                else if (entity instanceof Item item) {
                    world.strikeLightningEffect(item.getLocation());
                    item.remove();
                }
            }
        }
    }

    // --- TRAIL EFFECT: Spawn particles while Wind Charge flies ---
    @EventHandler
    public void onWindChargeLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof WindCharge windCharge) {
            // --- ADJUST SPEED ---
            // Values below 1.0 slow the wind charge down so it's actually visible in
            // flight.
            // 1.0 = default speed, 0.5 = half speed, 0.3 = slow lob.
            double speedMultiplier = 1.5;
            windCharge.setVelocity(windCharge.getVelocity().multiply(speedMultiplier));
            Bukkit.getScheduler().runTaskTimer(plugin, (task) -> {
                // Stop task if the wind charge hits something or is removed
                if (!windCharge.isValid() || windCharge.isDead()) {
                    task.cancel();
                    return;
                }

                World world = windCharge.getWorld();
                Location loc = windCharge.getLocation();

                // Spawn trail particles
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 1, 0.1, 0.1, 0.1, 0.02);
            }, 0L, 1L);
        }
    }
}