package org.example;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
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
                launchCluster(player.getWorld(), player.getEyeLocation(),
                        player.getLocation().getDirection().multiply(1.8));
            }
        }
    }

    // --- QOL: Fire CocoPops out of a dispenser ---
    @EventHandler
    public void onDispense(BlockDispenseEvent event) {
        if (!isCocoPops(event.getItem()))
            return;

        // We handle the launch ourselves instead of the vanilla drop.
        event.setCancelled(true);

        Block dispenser = event.getBlock();
        World world = dispenser.getWorld();
        Vector direction = dispenserDirection(dispenser);
        Location spawnLoc = dispenser.getLocation().add(0.5, 0.5, 0.5).add(direction.clone().multiply(0.7));

        // Defer to next tick so we don't consume/spawn mid-dispense.
        new BukkitRunnable() {
            @Override
            public void run() {
                consumeOneFromDispenser(dispenser);
                launchCluster(world, spawnLoc, direction.clone().multiply(1.8));
            }
        }.runTask(plugin);
    }

    // Spawns the CocoPops carrier projectile heading along the given velocity.
    private void launchCluster(World world, Location from, Vector velocity) {
        Snowball snowball = world.spawn(from, Snowball.class);
        snowball.setItem(new ItemStack(Material.COCOA_BEANS));
        snowball.setVelocity(velocity);
        snowball.setCustomName("CocoPopsCluster");
        world.playSound(from, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0f, 0.5f);
    }

    // Facing direction of a dispenser (falls back to straight up).
    private Vector dispenserDirection(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Directional directional) {
            BlockFace face = directional.getFacing();
            return new Vector(face.getModX(), face.getModY(), face.getModZ());
        }
        return new Vector(0, 1, 0);
    }

    // Removes a single CocoPops item from the dispenser's inventory.
    private void consumeOneFromDispenser(Block block) {
        if (block.getState() instanceof Dispenser dispenser) {
            Inventory inv = dispenser.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack slot = inv.getItem(i);
                if (isCocoPops(slot)) {
                    if (slot.getAmount() <= 1) {
                        inv.setItem(i, null);
                    } else {
                        slot.setAmount(slot.getAmount() - 1);
                    }
                    break;
                }
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
            if (world == null)
                return;

            // The CocoPops lands and "sits" on the floor before splitting
            spawnSittingCharge(world, hitLoc.clone());
        }
    }

    // --- Stage 1: The main charge lands, sits for 0.8s, then splits ---
    private void spawnSittingCharge(World world, Location origin) {
        // Drop a single cocoa bean that rests on the ground as the primary charge
        Item charge = world.dropItem(origin, new ItemStack(Material.COCOA_BEANS));
        charge.setPickupDelay(Integer.MAX_VALUE);
        charge.setVelocity(new Vector(0, 0, 0)); // sit still

        world.playSound(origin, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.5f);

        // Idle "fuse" particles while it sits
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks >= 16 || !charge.isValid()) {
                    this.cancel();
                    return;
                }
                world.spawnParticle(Particle.SMOKE, charge.getLocation().add(0, 0.2, 0), 2, 0.05, 0.05, 0.05, 0.01);
                ticks += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);

        // After 0.8 seconds (16 ticks) -> split into scattered cocoa beans
        new BukkitRunnable() {
            @Override
            public void run() {
                Location splitLoc = charge.isValid() ? charge.getLocation() : origin;
                charge.remove();
                splitIntoBomblets(world, splitLoc);
            }
        }.runTaskLater(plugin, 16L);
    }

    // --- Stage 2: Split into multiple cocoa beans fired in random directions ---
    private void splitIntoBomblets(World world, Location origin) {
        int clusterAmount = 8; // number of cocoa-bean bomblets
        double baseSpeed = 12.0; // base launch power (scaled down by RNG below)

        world.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.6f);
        world.spawnParticle(Particle.CRIT, origin, 20, 0.2, 0.2, 0.2, 0.2);

        for (int i = 0; i < clusterAmount; i++) {
            // RNG direction based on the CrackShot cluster-bomb model: mostly-upward pitch,
            // random yaw
            Location dirLoc = origin.clone();
            dirLoc.setPitch(-(random.nextInt(90) + random.nextInt(90)));
            dirLoc.setYaw(random.nextInt(360));

            // Speed with random falloff so each bomblet travels a different distance
            double speed = baseSpeed * (100 - random.nextInt(25) - random.nextInt(25)) * 0.001D;
            Vector velocity = dirLoc.getDirection().multiply(speed);

            spawnBomblet(world, origin.clone(), velocity);
        }
    }

    // --- Helper Method: Launch a single cocoa-bean bomblet that explodes ---
    private void spawnBomblet(World world, Location origin, Vector velocity) {
        Item droppedItem = world.dropItem(origin, new ItemStack(Material.COCOA_BEANS));
        droppedItem.setPickupDelay(Integer.MAX_VALUE); // Prevent players from picking it up
        droppedItem.setVelocity(velocity);

        // Delay secondary explosion so bomblets scatter before detonating in different
        // spots
        new BukkitRunnable() {
            @Override
            public void run() {
                Location explodeLoc = droppedItem.isValid() ? droppedItem.getLocation() : origin;

                // 1. Create explosion visual & sound effect (0 power so it doesn't break
                // blocks)
                world.createExplosion(explodeLoc, 0.0f, false, false);
                world.spawnParticle(Particle.EXPLOSION_EMITTER, explodeLoc, 1);

                // 2. Custom Radius Damage: Deal 6.0 damage (3 hearts) per bomblet to nearby
                // mobs/players
                double damageRadius = 4;
                double bombletDamage = 6.0; // 6.0 = 3 Hearts of damage per bomblet

                for (Entity entity : world.getNearbyEntities(explodeLoc, damageRadius, damageRadius,
                        damageRadius)) {
                    if (entity instanceof LivingEntity livingEntity) {

                        // Reset damage invulnerability ticks so multiple bomblets can hit the same
                        // target!
                        livingEntity.setNoDamageTicks(0);

                        // Deal damage directly
                        livingEntity.damage(bombletDamage);

                        // Apply knockback outwards from the bomblet
                        Vector knockback = livingEntity.getLocation().toVector().subtract(explodeLoc.toVector())
                                .normalize().multiply(0.4).setY(0.2);
                        if (!Double.isNaN(knockback.getX())) {
                            livingEntity.setVelocity(livingEntity.getVelocity().add(knockback));
                        }
                    }
                }

                // Remove item entity
                droppedItem.remove();
            }
        }.runTaskLater(plugin, 30L + random.nextInt(11)); // Randomized 1.5s - 2.0s delay for pop effect
    }

    // --- Helper Method: Check Item Custom Name ---
    private boolean isCocoPops(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta())
            return false;
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