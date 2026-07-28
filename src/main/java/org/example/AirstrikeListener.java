package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AirstrikeListener implements Listener {

    private final Plugin plugin;
    private final Random random = new Random();

    public AirstrikeListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // --- 1. Throw Marker Flare ---
    @EventHandler
    public void onLaunch(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = player.getInventory().getItemInMainHand();

            if (isAirstrike(item)) {
                event.setCancelled(true);

                Snowball flare = player.launchProjectile(Snowball.class);
                flare.setVelocity(player.getLocation().getDirection().multiply(1.5));
                flare.setCustomName("AirstrikeMarker");
                flare.setItem(new ItemStack(Material.AIR)); // Invisible carrier

                Item thrownTorch = player.getWorld().dropItem(player.getEyeLocation(), new ItemStack(Material.REDSTONE_TORCH));
                thrownTorch.setPickupDelay(Integer.MAX_VALUE);
                flare.addPassenger(thrownTorch);

                // Flying Particle Trail
                Bukkit.getScheduler().runTaskTimer(plugin, (task) -> {
                    if (!flare.isValid() || flare.isDead()) {
                        task.cancel();
                        return;
                    }

                    World world = flare.getWorld();
                    Location loc = flare.getLocation();

                    Particle.DustOptions redDust = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.2f);
                    world.spawnParticle(Particle.DUST, loc, 5, 0.05, 0.05, 0.05, 0.0, redDust);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, loc, 3, 0.05, 0.05, 0.05, 0.02);
                    world.spawnParticle(Particle.FLAME, loc, 1, 0.02, 0.02, 0.02, 0.01);

                }, 0L, 1L);

                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_EGG_THROW, 1.0f, 0.5f);
            }
        }
    }

    // --- 2. Marker Impact -> Trigger Laser, Siren, Jet Flyover & Artillery ---
    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Snowball flare && "AirstrikeMarker".equals(flare.getCustomName())) {

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

            // Lightning Bolt Visual
            world.strikeLightningEffect(targetLoc);

            // Signal Smoke
            world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, targetLoc, 50, 0.2, 0.5, 0.2, 0.05);

            // 1. Activate Red Laser Column for 2.5s
            spawnRedLaserBeam(world, targetLoc, 50);

            // 2. Play warning siren
            playWarningSiren(world, targetLoc);

            // 3. Trigger Jet Flyover + Bombardment after 2.5s delay
            new BukkitRunnable() {
                @Override
                public void run() {
                    simulateJetFlyover(world, targetLoc);
                    startArtilleryRain(world, targetLoc);
                }
            }.runTaskLater(plugin, 50L);
        }
    }

    // --- Red Laser Beam Column ---
    private void spawnRedLaserBeam(World world, Location targetLoc, int durationTicks) {
        Particle.DustOptions redLaser = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.8f);

        new BukkitRunnable() {
            int ticksElapsed = 0;

            @Override
            public void run() {
                if (ticksElapsed >= durationTicks) {
                    this.cancel();
                    return;
                }

                // Render red particle column 35 blocks high
                for (int y = 0; y < 35; y += 1) {
                    Location beamPoint = targetLoc.clone().add(0, y, 0);
                    world.spawnParticle(Particle.DUST, beamPoint, 2, 0.05, 0.05, 0.05, 0.0, redLaser);
                }

                ticksElapsed += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // --- Warning Siren ---
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

    // --- Jet Fighter Flyover (Contrails & Short Sound Cutoff) ---
    private void simulateJetFlyover(World world, Location targetLoc) {
        // Play boosted jet sound
        world.playSound(targetLoc, Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 10.0f, 0.5f);
        world.playSound(targetLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 10.0f, 0.6f);

        // Jet contrails across the sky
        new BukkitRunnable() {
            int step = -25;

            @Override
            public void run() {
                if (step > 25) {
                    this.cancel();
                    return;
                }

                Location jetPos = targetLoc.clone().add(step * 2, 35, 0);
                world.spawnParticle(Particle.CLOUD, jetPos, 10, 0.3, 0.3, 0.3, 0.02);
                world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, jetPos, 5, 0.2, 0.2, 0.2, 0.01);

                step += 2;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // --- Rain Bombs from Sky ---
    private void startArtilleryRain(World world, Location targetLoc) {
        int totalBombs = 10;
        int spreadRadius = 8;

        new BukkitRunnable() {
            int bombsDropped = 0;

            @Override
            public void run() {
                if (bombsDropped >= totalBombs) {
                    this.cancel();
                    return;
                }

                double offsetX = (random.nextDouble() - 0.5) * (spreadRadius * 2);
                double offsetZ = (random.nextDouble() - 0.5) * (spreadRadius * 2);
                Location skySpawn = targetLoc.clone().add(offsetX, 32, offsetZ);

                Fireball bomb = world.spawn(skySpawn, Fireball.class);
                bomb.setDirection(new Vector(0, -1, 0));
                bomb.setYield(3.0f);
                bomb.setIsIncendiary(false);

                world.playSound(skySpawn, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0f, 0.6f);

                bombsDropped++;
            }
        }.runTaskTimer(plugin, 0L, 4L);
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