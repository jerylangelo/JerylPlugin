package org.example;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CocoPopsListener implements Listener {

    private final Plugin plugin;
    private final Random random = new Random();

    public CocoPopsListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // --- 1. Right-Click to Launch CocoPops ---
    @EventHandler
    public void onLaunch(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = player.getInventory().getItemInMainHand();

            // Check if the item held is our special CocoPops item
            if (isCocoPops(item)) {
                event.setCancelled(true);

                // Shoot a fast snowball as the carrier projectile
                Snowball snowball = player.launchProjectile(Snowball.class);
                snowball.setVelocity(player.getLocation().getDirection().multiply(1.8));
                snowball.setCustomName("CocoPopsCluster");

                // Play throw sound
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 0.5f);
            }
        }
    }

    // --- 2. Main Detonation & Cluster Split ---
    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Snowball snowball && "CocoPopsCluster".equals(snowball.getCustomName())) {

            Location hitLoc;
            if (event.getHitEntity() != null) {
                hitLoc = event.getHitEntity().getLocation();
            } else if (event.getHitBlock() != null) {
                hitLoc = event.getHitBlock().getLocation().add(0, 1, 0);
            } else {
                hitLoc = snowball.getLocation();
            }

            World world = hitLoc.getWorld();
            if (world == null) return;

            // Initial Main Explosion
            world.createExplosion(hitLoc, 2.5f, false, false); // power, setFire, breakBlocks

            // Spawn 8 Mini Cluster Bomblets
            int clusterAmount = 8;
            for (int i = 0; i < clusterAmount; i++) {
                spawnBomblet(world, hitLoc);
            }
        }
    }

    // --- Helper Method: Spawn & Scatter Bomblet ---
    private void spawnBomblet(World world, Location origin) {
        // Drop a Cocoa Bean as the visual bomblet
        ItemStack bombletItem = new ItemStack(Material.COCOA_BEANS);
        Item droppedItem = world.dropItem(origin, bombletItem);
        droppedItem.setPickupDelay(Integer.MAX_VALUE); // Prevent players from picking it up

        // Scatter in a random outward vector
        double vx = (random.nextDouble() - 0.5) * 0.8;
        double vy = 0.3 + (random.nextDouble() * 0.4);
        double vz = (random.nextDouble() - 0.5) * 0.8;
        droppedItem.setVelocity(new Vector(vx, vy, vz));

        // Delay secondary explosion by ~10 ticks (0.5 seconds)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (droppedItem.isValid()) {
                    Location explodeLoc = droppedItem.getLocation();

                    // 1. Create explosion visual & sound effect (0 power so it doesn't duplicate damage)
                    world.createExplosion(explodeLoc, 0.0f, false, false);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, explodeLoc, 1);

                    // 2. Custom Radius Damage: Deal 6.0 damage (3 hearts) per bomblet to nearby mobs/players
                    double damageRadius = 2.5;
                    double bombletDamage = 6.0; // 6.0 = 3 Hearts of damage per bomblet

                    for (Entity entity : world.getNearbyEntities(explodeLoc, damageRadius, damageRadius, damageRadius)) {
                        if (entity instanceof LivingEntity livingEntity) {

                            // Reset damage invulnerability ticks so multiple bomblets can hit the same target!
                            livingEntity.setNoDamageTicks(0);

                            // Deal damage directly
                            livingEntity.damage(bombletDamage);

                            // Apply knockback outwards from the bomblet
                            Vector knockback = livingEntity.getLocation().toVector().subtract(explodeLoc.toVector()).normalize().multiply(0.4).setY(0.2);
                            if (!Double.isNaN(knockback.getX())) {
                                livingEntity.setVelocity(livingEntity.getVelocity().add(knockback));
                            }
                        }
                    }

                    // Remove item entity
                    droppedItem.remove();
                }
            }
        }.runTaskLater(plugin, 10L + random.nextInt(6)); // Slightly randomized delay for pop effect
    }

    // --- Helper Method: Check Item Custom Name ---
    private boolean isCocoPops(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName().contains("CocoPops");
    }

    // --- Utility Method: Create the CocoPops Item ---
    public static ItemStack createCocoPopsItem() {
        ItemStack item = new ItemStack(Material.COCOA_BEANS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "CocoPops");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + "Cluster Bomb");
            lore.add(ChatColor.GRAY + "Description: " + ChatColor.WHITE + "Wilson's favourite cereal");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}