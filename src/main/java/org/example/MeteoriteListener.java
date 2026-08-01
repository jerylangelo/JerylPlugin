package org.example;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class MeteoriteListener implements Listener {

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 500;

    // How far the player can be looking for us to find a target block
    private static final double TARGET_RANGE = 100.0;

    // Meteorites always start this many blocks above the target.
    private static final double SPAWN_HEIGHT = 100.0;
    // Impact must happen within this many ticks (10 seconds) no matter the size.
    private static final long MAX_FALL_TICKS = 200L;

    // Per-tick work budgets. Destructive setType is the heaviest (lighting/section
    // updates), reads are cheap, restores are moderate. Destructive/restore sit at
    // 20k (a good balance of speed vs. TPS impact); the read-only capture pass can
    // run
    // much higher since it doesn't touch the lighting engine.
    private static final int CARVE_BUDGET_PER_TICK = 35000;
    private static final int CAPTURE_BUDGET_PER_TICK = 60000;
    private static final int RESTORE_BUDGET_PER_TICK = 35000;

    // Rollback only applies to craters larger than this radius.
    private static final int ROLLBACK_MIN_SIZE = 5;
    // Crater stays this long (20 seconds) before it silently heals.
    private static final long ROLLBACK_DELAY_TICKS = 400L;

    // Each player may only have this many meteorites in the air at once.
    private static final int MAX_PER_PLAYER = 3;

    // Materials scattered along the crater floor for a scorched, molten look.
    private static final Material[] SCORCH_BLOCKS = {
            Material.MAGMA_BLOCK, Material.BLACKSTONE, Material.BASALT, Material.OBSIDIAN
    };

    private final Plugin plugin;
    private final Random random = new Random();
    private final NamespacedKey sizeKey;
    private final NamespacedKey rollbackKey;

    // How many meteorites each player currently has incoming.
    private final Map<UUID, Integer> activeMeteorites = new HashMap<>();

    public MeteoriteListener(Plugin plugin) {
        this.plugin = plugin;
        this.sizeKey = new NamespacedKey(plugin, "meteorite_size");
        this.rollbackKey = new NamespacedKey(plugin, "meteorite_rollback");
    }

    // A single meteorite strike from launch to rollback.
    private static final class MeteorJob {
        final World world;
        final Location center;
        final int size;
        final boolean rollback;
        final UUID owner;
        final List<Cell> cells; // captured crater blocks (rollback only)

        boolean captured; // pre-capture pass finished
        boolean impacted; // fireball has landed
        boolean applying; // destruction has started
        boolean resolved; // per-player counter already decremented

        MeteorJob(World world, Location center, int size, boolean rollback, UUID owner) {
            this.world = world;
            this.center = center;
            this.size = size;
            this.rollback = rollback;
            this.owner = owner;
            this.cells = rollback ? new ArrayList<>() : null;
        }
    }

    // One affected block. For rollback we keep enough to fully restore it.
    private static final class Cell {
        final Block block;
        final boolean scorch;
        BlockData originalData; // captures facing/axis/waterlogged/etc.
        BlockState originalState; // captures NBT for tile entities (chests, signs...)

        Cell(Block block, boolean scorch) {
            this.block = block;
            this.scorch = scorch;
        }
    }

    @FunctionalInterface
    private interface CellConsumer {
        void accept(Block block, boolean scorch);
    }

    // --- 1. Right-Click to call down a meteorite ---
    @EventHandler
    public void onLaunch(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isMeteorite(item))
            return;

        event.setCancelled(true);

        // Per-player limit on impending meteorites
        int active = activeMeteorites.getOrDefault(player.getUniqueId(), 0);
        if (active >= MAX_PER_PLAYER) {
            player.sendMessage(ChatColor.RED + "You already have " + MAX_PER_PLAYER + " meteorites incoming!");
            return;
        }

        int size = readSize(item);
        boolean rollback = readRollback(item);

        // Find the block the player is looking at
        RayTraceResult trace = player.rayTraceBlocks(TARGET_RANGE);
        if (trace == null || trace.getHitBlock() == null) {
            player.sendMessage(ChatColor.RED + "You must be facing a block to call down a meteorite!");
            return;
        }

        Block targetBlock = trace.getHitBlock();
        World world = targetBlock.getWorld();
        Location center = targetBlock.getLocation().add(0.5, 1.0, 0.5);

        activeMeteorites.put(player.getUniqueId(), active + 1);

        boolean willRollback = rollback && size > ROLLBACK_MIN_SIZE;
        player.sendMessage(ChatColor.GOLD + "Meteorite incoming! " + ChatColor.GRAY
                + "(size " + size + (willRollback ? ", auto-rollback" : "") + ")");

        // Draw the marker ring instantly, then keep it pulsing.
        drawRing(world, center, size);
        drawTargetCircle(world, center, size);

        MeteorJob job = new MeteorJob(world, center, size, willRollback, player.getUniqueId());

        // Start snapshotting the crater NOW (while the meteorite falls) so impact is
        // cheap.
        if (job.rollback) {
            startCapture(job);
        }

        // Short delay so the ring is visible before the fireball appears.
        new BukkitRunnable() {
            @Override
            public void run() {
                launchMeteor(job);
            }
        }.runTaskLater(plugin, 10L);
    }

    // --- 2. Marker ring ---
    private void drawRing(World world, Location center, double radius) {
        int points = Math.max(24, Math.min((int) (radius * 6), 400));
        Particle.DustOptions redDust = new Particle.DustOptions(Color.fromRGB(255, 40, 0), 2.0f);
        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            Location ring = new Location(world, x, center.getY() + 0.2, z);
            world.spawnParticle(Particle.DUST, ring, 1, 0, 0, 0, 0, redDust);
            world.spawnParticle(Particle.FLAME, ring, 1, 0, 0, 0, 0.0);
        }
    }

    private void drawTargetCircle(World world, Location center, int size) {
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks >= 40) {
                    this.cancel();
                    return;
                }
                drawRing(world, center, size);
                ticks += 4;
            }
        }.runTaskTimer(plugin, 4L, 4L);
    }

    // --- 3. Launch a fire charge from Y+100 at an angle, timed to the size ---
    private void launchMeteor(MeteorJob job) {
        World world = job.world;
        Location center = job.center;
        int size = job.size;

        double horizontal = Math.min(60.0, 20.0 + size);
        double yaw = random.nextDouble() * 2 * Math.PI;
        Location spawn = center.clone().add(
                Math.cos(yaw) * horizontal,
                SPAWN_HEIGHT,
                Math.sin(yaw) * horizontal);

        Vector toCenter = center.toVector().subtract(spawn.toVector());
        double distance = toCenter.length();
        Vector direction = toCenter.normalize();

        // Time-to-impact grows with size but is capped at 10 seconds. Velocity is then
        // derived from the distance so the meteorite reaches the centre exactly on
        // time.
        final long fallTicks = Math.min(MAX_FALL_TICKS, 40L + (long) (size * 3.5));
        final double speedPerTick = distance / fallTicks;

        LargeFireball meteor = world.spawn(spawn, LargeFireball.class);
        meteor.setYield(0.0f);
        meteor.setIsIncendiary(false);
        meteor.setDirection(direction);
        meteor.setVelocity(direction.clone().multiply(speedPerTick));
        meteor.setAcceleration(new Vector(0, 0, 0));

        world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 4.0f, 0.4f);

        // Drive the fireball ourselves for exact timing + guaranteed centre hit.
        new BukkitRunnable() {
            long t = 0;

            @Override
            public void run() {
                if (t >= fallTicks) {
                    if (meteor.isValid()) {
                        meteor.remove();
                    }
                    onMeteorImpact(job);
                    this.cancel();
                    return;
                }
                if (meteor.isValid() && !meteor.isDead()) {
                    // Re-assert velocity each tick to counter the fireball's drag.
                    meteor.setVelocity(direction.clone().multiply(speedPerTick));
                    Location loc = meteor.getLocation();
                    world.spawnParticle(Particle.FLAME, loc, 8, 0.3, 0.3, 0.3, 0.02);
                    world.spawnParticle(Particle.LARGE_SMOKE, loc, 4, 0.2, 0.2, 0.2, 0.01);
                    world.spawnParticle(Particle.LAVA, loc, 2, 0.2, 0.2, 0.2, 0.0);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // --- 4. Impact ---
    private void onMeteorImpact(MeteorJob job) {
        resolve(job); // this meteorite is no longer "impending"

        World world = job.world;
        Location center = job.center;
        int size = job.size;

        // Impact visuals + sound
        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 5, size * 0.15, size * 0.15, size * 0.15, 0);
        world.spawnParticle(Particle.FLAME, center, 200, size * 0.3, size * 0.3, size * 0.3, 0.05);
        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 6.0f, 0.5f);
        world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 6.0f, 0.6f);

        // Damage nearby living entities within the blast sphere
        double radius = size;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (entity instanceof LivingEntity living && living.getLocation().distance(center) <= radius) {
                living.setNoDamageTicks(0);
                living.damage(50.0);
                Vector knockback = living.getLocation().toVector().subtract(center.toVector());
                if (knockback.lengthSquared() > 0) {
                    knockback.normalize().multiply(1.2).setY(0.6);
                    living.setVelocity(living.getVelocity().add(knockback));
                }
            }
        }

        job.impacted = true;
        if (job.rollback) {
            tryApply(job); // waits until pre-capture has finished
        } else {
            streamingCarve(job);
        }
    }

    // Runs the destruction once BOTH the pre-capture and the impact are done.
    private void tryApply(MeteorJob job) {
        if (job.captured && job.impacted && !job.applying) {
            job.applying = true;
            applyFromCapturedCells(job);
        }
    }

    // --- 5a. Pre-capture (rollback): snapshot crater blocks during the fall ---
    private void startCapture(MeteorJob job) {
        final CraterCursor cursor = new CraterCursor(job.world, job.center, job.size);
        new BukkitRunnable() {
            @Override
            public void run() {
                boolean done = cursor.advance(CAPTURE_BUDGET_PER_TICK, (block, scorch) -> {
                    Material type = block.getType();
                    if (type == Material.AIR || type == Material.BEDROCK
                            || type.toString().contains("PORTAL")) {
                        return;
                    }
                    Cell cell = new Cell(block, scorch);
                    cell.originalData = block.getBlockData();
                    BlockState state = block.getState();
                    if (state instanceof TileState) {
                        // Only tile entities need a full (heavier) state snapshot for NBT.
                        cell.originalState = state;
                    }
                    job.cells.add(cell);
                });
                if (done) {
                    job.captured = true;
                    this.cancel();
                    tryApply(job);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // --- 5b. Apply destruction from the captured cells (fast: no shape math) ---
    private void applyFromCapturedCells(MeteorJob job) {
        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                int budget = CARVE_BUDGET_PER_TICK;
                while (budget-- > 0) {
                    if (index >= job.cells.size()) {
                        this.cancel();
                        scheduleRollback(job);
                        return;
                    }
                    Cell cell = job.cells.get(index++);
                    Material set = cell.scorch
                            ? SCORCH_BLOCKS[random.nextInt(SCORCH_BLOCKS.length)]
                            : Material.AIR;
                    cell.block.setType(set, false);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // --- 5c. Non-rollback: carve straight from the shape, storing nothing ---
    private void streamingCarve(MeteorJob job) {
        final CraterCursor cursor = new CraterCursor(job.world, job.center, job.size);
        new BukkitRunnable() {
            @Override
            public void run() {
                boolean done = cursor.advance(CARVE_BUDGET_PER_TICK, (block, scorch) -> {
                    Material type = block.getType();
                    if (type == Material.AIR || type == Material.BEDROCK
                            || type.toString().contains("PORTAL")) {
                        return;
                    }
                    Material set = scorch
                            ? SCORCH_BLOCKS[random.nextInt(SCORCH_BLOCKS.length)]
                            : Material.AIR;
                    block.setType(set, false);
                });
                if (done) {
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // --- 6. Rollback: silently restore every captured block after the delay ---
    private void scheduleRollback(MeteorJob job) {
        new BukkitRunnable() {
            @Override
            public void run() {
                new BukkitRunnable() {
                    int index = 0;

                    @Override
                    public void run() {
                        int budget = RESTORE_BUDGET_PER_TICK;
                        while (budget-- > 0) {
                            if (index >= job.cells.size()) {
                                this.cancel();
                                return;
                            }
                            Cell cell = job.cells.get(index++);
                            // Restore silently: no sounds, no particles.
                            if (cell.originalState != null) {
                                cell.originalState.update(true, false);
                            } else {
                                cell.block.setBlockData(cell.originalData, false);
                            }
                        }
                    }
                }.runTaskTimer(plugin, 0L, 1L);
            }
        }.runTaskLater(plugin, ROLLBACK_DELAY_TICKS);
    }

    // Decrements the player's active-meteorite counter exactly once.
    private void resolve(MeteorJob job) {
        if (job.resolved) {
            return;
        }
        job.resolved = true;
        int count = activeMeteorites.getOrDefault(job.owner, 0);
        if (count <= 1) {
            activeMeteorites.remove(job.owner);
        } else {
            activeMeteorites.put(job.owner, count - 1);
        }
    }

    // Resumable sphere walker. Skips the corners of the bounding cube (columns that
    // can't possibly intersect the sphere) and applies a per-block jittered radius
    // so
    // the crater rim looks like a real impact instead of a perfect ball.
    private final class CraterCursor {
        private final World world;
        private final int cx;
        private final int cy;
        private final int cz;
        private final int radius;
        private final int bound;
        private final int minY;
        private final int maxY;
        private final double jitter;
        private final double maxR2;

        private int dx;
        private int dy;
        private int dz;
        private int zLimit;
        private boolean done;

        CraterCursor(World world, Location center, int radius) {
            this.world = world;
            this.radius = radius;
            this.cx = center.getBlockX();
            this.cy = center.getBlockY();
            this.cz = center.getBlockZ();
            this.jitter = Math.max(1.0, radius * 0.12);
            int pad = Math.max(1, (int) Math.ceil(jitter));
            this.bound = radius + pad;
            double maxR = radius + jitter;
            this.maxR2 = maxR * maxR;
            this.minY = world.getMinHeight();
            this.maxY = world.getMaxHeight() - 1;
            this.dx = -bound;
            this.dy = -bound;
            setColumnOrAdvance();
        }

        // Positions dz for the current (dx, dy) column, skipping empty columns.
        private void setColumnOrAdvance() {
            while (true) {
                double arg = maxR2 - (double) dx * dx - (double) dy * dy;
                if (arg >= 0) {
                    zLimit = (int) Math.floor(Math.sqrt(arg));
                    dz = -zLimit;
                    return;
                }
                dy++;
                if (dy > bound) {
                    dy = -bound;
                    dx++;
                }
                if (dx > bound) {
                    done = true;
                    return;
                }
            }
        }

        // Processes up to 'budget' candidate cells; returns true when the whole crater
        // has been walked.
        boolean advance(int budget, CellConsumer consumer) {
            int ops = 0;
            while (ops < budget && !done) {
                double dist = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                double threshold = radius + (random.nextDouble() * 2.0 - 1.0) * jitter;
                if (dist <= threshold) {
                    int by = cy + dy;
                    if (by >= minY && by <= maxY) {
                        Block block = world.getBlockAt(cx + dx, by, cz + dz);
                        boolean scorch = dy < 0 && dist >= threshold - 1.5 && random.nextDouble() < 0.35;
                        consumer.accept(block, scorch);
                    }
                }
                ops++;

                dz++;
                if (dz > zLimit) {
                    dy++;
                    if (dy > bound) {
                        dy = -bound;
                        dx++;
                    }
                    if (dx > bound) {
                        done = true;
                    } else {
                        setColumnOrAdvance();
                    }
                }
            }
            return done;
        }
    }

    // --- Helpers ---

    private boolean isMeteorite(ItemStack item) {
        if (item == null || item.getType() != Material.FIRE_CHARGE || !item.hasItemMeta())
            return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(sizeKey, PersistentDataType.INTEGER);
    }

    private int readSize(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return MIN_SIZE;
        Integer stored = meta.getPersistentDataContainer().get(sizeKey, PersistentDataType.INTEGER);
        if (stored == null)
            return MIN_SIZE;
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, stored));
    }

    // Reads the rollback flag; defaults to true (rollback on) if unset.
    private boolean readRollback(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return true;
        Byte stored = meta.getPersistentDataContainer().get(rollbackKey, PersistentDataType.BYTE);
        return stored == null || stored != 0;
    }

    // Builds a Meteorite fire charge that carries its size + rollback flag in
    // persistent data.
    public static ItemStack createMeteoriteItem(Plugin plugin, int size, boolean rollback) {
        int clamped = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        ItemStack item = new ItemStack(Material.FIRE_CHARGE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(
                    ChatColor.RED + "" + ChatColor.BOLD + "Meteorite " + ChatColor.GRAY + "[" + clamped + "]");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + "Orbital Strike");
            lore.add(ChatColor.GRAY + "Size: " + ChatColor.WHITE + clamped + " blocks");
            boolean effectiveRollback = rollback && clamped > ROLLBACK_MIN_SIZE;
            lore.add(ChatColor.GRAY + "Rollback: " + (effectiveRollback
                    ? ChatColor.GREEN + "On (heals after " + (ROLLBACK_DELAY_TICKS / 20) + "s)"
                    : ChatColor.RED + "Off"));
            lore.add(ChatColor.GRAY + "Right-click a block to call it down.");
            meta.setLore(lore);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(new NamespacedKey(plugin, "meteorite_size"), PersistentDataType.INTEGER, clamped);
            pdc.set(new NamespacedKey(plugin, "meteorite_rollback"), PersistentDataType.BYTE,
                    (byte) (rollback ? 1 : 0));

            item.setItemMeta(meta);
        }
        return item;
    }
}
