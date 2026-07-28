package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.*;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AirstrikeListener implements Listener {

    private final Plugin plugin;
    private final Random random = new Random();

    public AirstrikeListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // --- 1. Throw Marker Flare (Invisible Snowball + 3D Torch + Redstone Sparks) ---
    @EventHandler
    public void onLaunch(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = player.getInventory().getItemInMainHand();

            if (isAirstrike(item)) {
                event.setCancelled(true);

                // 1. Launch carrier snowball & make it completely invisible
                Snowball flare = player.launchProjectile(Snowball.class);
                flare.setVelocity(player.getLocation().getDirection().multiply(1.5));
                flare.setCustomName("AirstrikeMarker");
                flare.setItem(new ItemStack(Material.AIR)); // Makes the snowball texture invisible

                // 2. Spawn dropped 3D Redstone Torch item entity riding the snowball
                Item thrownTorch = player.getWorld().dropItem(player.getEyeLocation(), new ItemStack(Material.REDSTONE_TORCH));
                thrownTorch.setPickupDelay(Integer.MAX_VALUE); // Prevent players from picking it up
                flare.addPassenger(thrownTorch);

                // 3. Particle Trail: Redstone Sparks + Electric Sparks
                Bukkit.getScheduler().runTaskTimer(plugin, (task) -> {
                    if (!flare.isValid() || flare.isDead()) {
                        task.cancel();
                        return;
                    }

                    World world = flare.getWorld();
                    Location loc = flare.getLocation();

                    // Red dust particles (Redstone spark effect)
                    Particle.DustOptions redDust = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 0, 0), 1.2f);
                    world.spawnParticle(Particle.DUST, loc, 5, 0.05, 0.05, 0.05, 0.0, redDust);

                    // Flame/Spark particles for burning flare effect
                    world.spawnParticle(Particle.ELECTRIC_SPARK, loc, 3, 0.05, 0.05, 0.05, 0.02);
                    world.spawnParticle(Particle.FLAME, loc, 1, 0.02, 0.02, 0.02, 0.01);

                }, 0L, 1L); // Runs every tick (~0.05s)

                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EGG_THROW, 1.0f, 0.5f);
            }
        }
    }

    // --- 2. Marker Impact -> Trigger Airstrike Sequence ---
    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Snowball flare && "AirstrikeMarker".equals(flare.getCustomName())) {

            // Delete the riding Redstone Torch entity immediately upon hit
            flare.getPassengers().forEach(Entity::remove);

            Location targetLoc;
            if (event.getHitEntity() != null) {
                targetLoc = event.getHitEntity().getLocation();
            } else if (event.getHitBlock() != null) {
                targetLoc = event.getHitBlock().getLocation().add(0, 1, 0);
            } else {
                targetLoc = flare.getLocation();
            }

            World world = targetLoc.getWorld();
            if (world == null) return;

            // Strike Lightning Effect
            for (int i = 0; i < 3; i++) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    world.strikeLightningEffect(targetLoc);
                }, i * 3L);
            }
            // Spawn Red Signal Smoke at target
            world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, targetLoc, 50, 0.2, 0.5, 0.2, 0.05);
            world.playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.5f);

            // Warning siren sequence
            playWarningSiren(world, targetLoc);

            // Delay artillery rain by 2.5 seconds (50 ticks)
            new BukkitRunnable() {
                @Override
                public void run() {
                    startArtilleryRain(world, targetLoc);
                }
            }.runTaskLater(plugin, 50L);
        }
    }

    // --- Play Siren Sound ---
    private void playWarningSiren(World world, Location targetLoc) {
        new BukkitRunnable() {
            int beeps = 0;
            @Override
            public void run() {
                if (beeps >= 5) {
                    this.cancel();
                    return;
                }
                world.playSound(targetLoc, Sound.BLOCK_NOTE_BLOCK_BELL, 3.0f, 0.5f);
                beeps++;
            }
        }.runTaskTimer(plugin, 0L, 8L);
    }

    // --- Rain Bombs from Sky ---
    private void startArtilleryRain(World world, Location targetLoc) {
        int totalBombs = 10;
        int spreadRadius = 8; // Spread radius in blocks around target

        new BukkitRunnable() {
            int bombsDropped = 0;

            @Override
            public void run() {
                if (bombsDropped >= totalBombs) {
                    this.cancel();
                    return;
                }

                // Random position high in the sky (+30 blocks Y)
                double offsetX = (random.nextDouble() - 0.5) * (spreadRadius * 2);
                double offsetZ = (random.nextDouble() - 0.5) * (spreadRadius * 2);
                Location skySpawn = targetLoc.clone().add(offsetX, 30, offsetZ);

                // Spawn Fireball or TNT driving downwards
                Fireball bomb = world.spawn(skySpawn, Fireball.class);
                bomb.setDirection(new Vector(0, -1, 0)); // Downward vector
                bomb.setYield(3.0f); // Explosion size
                bomb.setIsIncendiary(false); // Set to true if you want fire

                world.playSound(skySpawn, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0f, 0.6f);

                bombsDropped++;
            }
        }.runTaskTimer(plugin, 0L, 4L); // Drops 1 bomb every 4 ticks (0.2s)
    }

    private boolean isAirstrike(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName().contains("Airstrike");
    }

    public static ItemStack createAirstrikeItem() {
        ItemStack item = new ItemStack(Material.REDSTONE_TORCH);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Airstrike Signal Flare");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + "Tactical Beacon");
            lore.add(ChatColor.GRAY + "Description: " + ChatColor.WHITE + "Calls down an aerial artillery strike!");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
