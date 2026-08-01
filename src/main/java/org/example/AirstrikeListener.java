package org.example;

import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
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

    // Scoreboard tag used to identify our falling-fence ordnance
    private static final String BOMB_TAG = "airstrike_fence_bomb";

    public AirstrikeListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // --- 1. Throw the Redstone Torch marker and wait for it to land ---
    @EventHandler
    public void onLaunch(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = player.getInventory().getItemInMainHand();

            if (isAirstrike(item)) {
                event.setCancelled(true);
                launchMarker(player.getWorld(), player.getEyeLocation(),
                        player.getLocation().getDirection().multiply(1.2));
            }
        }
    }

    // --- QOL: Throw the Airstrike marker out of a dispenser ---
    @EventHandler
    public void onDispense(BlockDispenseEvent event) {
        if (!isAirstrike(event.getItem()))
            return;

        // We throw the marker ourselves instead of the vanilla drop.
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
                launchMarker(world, spawnLoc, direction.clone().multiply(1.2));
            }
        }.runTask(plugin);
    }

    // Throws a Redstone Torch marker along the given velocity and watches it land.
    private void launchMarker(World world, Location from, Vector velocity) {
        Item thrownTorch = world.dropItem(from, new ItemStack(Material.REDSTONE_TORCH));
        thrownTorch.setPickupDelay(Integer.MAX_VALUE);
        thrownTorch.setVelocity(velocity);

        world.playSound(from, Sound.ENTITY_EGG_THROW, 1.0f, 0.5f);

        // Watch the marker until it settles on a surface, then trigger the strike
        trackMarkerUntilLanded(thrownTorch);
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

    // Removes a single Airstrike item from the dispenser's inventory.
    private void consumeOneFromDispenser(Block block) {
        if (block.getState() instanceof Dispenser dispenser) {
            Inventory inv = dispenser.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack slot = inv.getItem(i);
                if (isAirstrike(slot)) {
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

    // --- 2. Detect the marker landing, then (after a short delay) trigger the
    // strike ---
    private void trackMarkerUntilLanded(Item marker) {
        new BukkitRunnable() {
            int ticksAlive = 0;
            boolean triggered = false;

            @Override
            public void run() {
                // Marker vanished (picked up somehow / despawned) or never lands (void)
                if (!marker.isValid() || marker.isDead() || ticksAlive > 400) {
                    this.cancel();
                    return;
                }

                World world = marker.getWorld();
                Location loc = marker.getLocation();

                // Flying particle trail while it's still airborne
                Particle.DustOptions redDust = new Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.2f);
                world.spawnParticle(Particle.DUST, loc, 5, 0.05, 0.05, 0.05, 0.0, redDust);
                world.spawnParticle(Particle.ELECTRIC_SPARK, loc, 3, 0.05, 0.05, 0.05, 0.02);

                // Consider it "landed" once it has settled on the ground (give it a few
                // ticks first so it doesn't count the throw origin)
                if (!triggered && ticksAlive > 3 && marker.isOnGround()) {
                    triggered = true;
                    this.cancel();

                    Location targetLoc = marker.getLocation().clone();

                    // 1 - 2 second delay before the lightning strike (30 ticks = 1.5s)
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            // The lightning removes the dropped marker and kicks off the airstrike
                            if (marker.isValid()) {
                                marker.remove();
                            }
                            triggerAirstrike(targetLoc.getWorld(), targetLoc);
                        }
                    }.runTaskLater(plugin, 30L);
                    return;
                }

                ticksAlive++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // --- 3. The airstrike sequence: Lightning, Laser, Siren, Jet Flyover &
    // Artillery ---
    private void triggerAirstrike(World world, Location targetLoc) {
        if (world == null)
            return;

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

    // --- Rain Fence Bombs from Sky ---
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

                dropFenceBomb(world, skySpawn);
                bombsDropped++;
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }

    // Spawns a single falling-fence bomb and watches it until it lands.
    private void dropFenceBomb(World world, Location skySpawn) {
        // Spawn a falling fence as the incoming ordnance
        FallingBlock bomb = world.spawnFallingBlock(skySpawn, Material.OAK_FENCE.createBlockData());
        bomb.setDropItem(false); // never drop a fence item
        bomb.setCancelDrop(true); // never place a fence block on land
        bomb.setHurtEntities(false); // the explosion handles damage, not the block
        bomb.addScoreboardTag(BOMB_TAG);
        bomb.setVelocity(new Vector(0, -1.2, 0)); // drive it downward faster than gravity alone

        world.playSound(skySpawn, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0f, 0.6f);

        // Watch the bomb: trail while airborne, detonate wherever it settles.
        // We don't rely on EntityChangeBlockEvent (a falling block that lands on a
        // non-placeable/occupied spot silently vanishes instead of firing it), so we
        // track its landing directly. This also means nearby explosions can knock the
        // still-falling fences around before they land.
        new BukkitRunnable() {
            Location last = bomb.getLocation();
            int ticks = 0;
            boolean detonated = false;

            @Override
            public void run() {
                if (detonated) {
                    this.cancel();
                    return;
                }

                if (bomb.isValid() && !bomb.isDead()) {
                    last = bomb.getLocation();

                    // Smoke/flame trail so it reads as incoming ordnance
                    world.spawnParticle(Particle.SMOKE, last, 3, 0.05, 0.05, 0.05, 0.01);
                    world.spawnParticle(Particle.FLAME, last, 1, 0.02, 0.02, 0.02, 0.0);

                    ticks++;
                    if (bomb.isOnGround() || ticks > 200) {
                        detonated = true;
                        bomb.remove();
                        detonateFenceBomb(world, last);
                        this.cancel();
                    }
                } else {
                    // The entity is gone (landed & placement was cancelled, or removed):
                    // detonate at its last known location.
                    detonated = true;
                    detonateFenceBomb(world, last);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // Explosion that damages/knocks entities but leaves terrain intact.
    private void detonateFenceBomb(World world, Location loc) {
        // power 3.0, no fire, and breakBlocks = false so terrain stays intact.
        // createExplosion still applies knockback to nearby entities (including other
        // falling fences), so the bombs shove each other around on impact.
        world.createExplosion(loc, 3.0f, false, false);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
    }

    // --- Fence Bomb landing -> stop the fence from ever becoming a placed block
    // ---
    @EventHandler
    public void onBombLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock))
            return;
        if (!fallingBlock.getScoreboardTags().contains(BOMB_TAG))
            return;

        // Just cancel the placement; the landing watcher handles the explosion so a
        // fence is never left behind on the ground.
        event.setCancelled(true);
    }

    private boolean isAirstrike(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta())
            return false;
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