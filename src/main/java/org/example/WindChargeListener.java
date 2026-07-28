package org.example;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class WindChargeListener implements Listener {

    private static final double RADIUS = 5.0;
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
            if (world == null) return;

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
}