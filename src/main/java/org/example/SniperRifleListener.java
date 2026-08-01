package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class SniperRifleListener implements Listener {

    // Slowness amplifier used purely for its FOV-narrowing (zoom) side effect.
    private static final int SCOPE_AMPLIFIER = 4; // Slowness V
    private static final int SCOPE_DURATION = 20 * 60 * 60; // effectively "until toggled/fired"

    private final Plugin plugin;
    private final HashMap<UUID, Long> cooldowns = new HashMap<>();

    // Victims killed by the rifle in the last moment -> used to replace the
    // vanilla death message with our own attributed one. We store the shooter's
    // name
    // so attribution survives even a forced kill (creative targets, where damage()
    // does nothing and no vanilla killer is recorded).
    private final HashMap<UUID, RifleKill> recentRifleKills = new HashMap<>();

    private record RifleKill(String killerName, long time) {
    }

    public SniperRifleListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!isHuntingRifle(item))
            return;

        // Iron horse armour has no vanilla right/left-click behaviour in hand, so both
        // clicks arrive as normal interact events that we can handle cleanly.

        // RIGHT-CLICK: toggle the custom FOV zoom scope.
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true); // stop the vanilla spyglass from zooming
            toggleScope(player);
            return;
        }

        // LEFT-CLICK: fire the rifle.
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            tryShootRifle(player);
        }
    }

    // Toggles the FOV zoom on/off using Slowness for its zoom side effect.
    private void toggleScope(Player player) {
        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.playSound(player.getLocation(), Sound.ITEM_SPYGLASS_STOP_USING, 1.0f, 1.2f);
        } else {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, SCOPE_DURATION, SCOPE_AMPLIFIER, false, false, false));
            player.playSound(player.getLocation(), Sound.ITEM_SPYGLASS_USE, 1.0f, 1.0f);
        }
    }

    // Clears the scope zoom (called when firing).
    private void clearScope(Player player) {
        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }
    }

    private void tryShootRifle(Player player) {
        long now = System.currentTimeMillis();
        if (cooldowns.containsKey(player.getUniqueId())) {
            long lastShot = cooldowns.get(player.getUniqueId());
            if (now - lastShot < 1200) { // 1.2s Cooldown
                return;
            }
        }
        cooldowns.put(player.getUniqueId(), now);

        shootRifle(player);
    }

    private void shootRifle(Player player) {
        World world = player.getWorld();
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();

        // 1. Audio & Recoil Pushback
        world.playSound(eyeLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 2.0f);
        world.playSound(eyeLoc, Sound.ITEM_SPYGLASS_STOP_USING, 1.0f, 0.5f);
        player.setVelocity(player.getVelocity().add(direction.clone().multiply(-0.25)));

        // Firing unscopes the rifle
        clearScope(player);

        // 2. Hitscan Raytrace (120 Blocks)
        // raySize kept small (0.1) so the reported hit position is precise -- a large
        // ray size inflates every hitbox and makes headshot detection unreliable.
        double maxDistance = 120.0;
        RayTraceResult rayTrace = world.rayTrace(
                eyeLoc,
                direction,
                maxDistance,
                org.bukkit.FluidCollisionMode.NEVER,
                true,
                0.1,
                (entity) -> entity instanceof LivingEntity && !entity.getUniqueId().equals(player.getUniqueId()));

        double actualDistance = maxDistance;
        if (rayTrace != null && rayTrace.getHitPosition() != null) {
            actualDistance = eyeLoc.distance(rayTrace.getHitPosition().toLocation(world));
        }

        // 3. Bullet Trail Particles
        Particle.DustOptions bulletDust = new Particle.DustOptions(Color.fromRGB(220, 220, 220), 0.8f);
        for (double d = 1.0; d < actualDistance; d += 0.8) {
            Location point = eyeLoc.clone().add(direction.clone().multiply(d));
            world.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, bulletDust);
            world.spawnParticle(Particle.CRIT, point, 1, 0.02, 0.02, 0.02, 0.01);
        }

        // 4. Hit Detection, Damage, & Kill Handling
        if (rayTrace != null && rayTrace.getHitEntity() instanceof LivingEntity target) {
            target.setNoDamageTicks(0);

            // Headshot Detection
            // Measure the impact height relative to the entity's feet, then compare it
            // against the entity's own eye height. A hit at (or above) eye level counts
            // as a headshot, with a small tolerance for the upper neck. This scales
            // correctly across differently-sized mobs instead of using a flat 0.5 window.
            double baseY = target.getLocation().getY();
            double hitRelativeY = rayTrace.getHitPosition().getY() - baseY;
            double eyeHeight = target.getEyeHeight();
            double headThreshold = eyeHeight - 0.15; // allow just below the eyes (upper head/neck)
            boolean isHeadshot = hitRelativeY >= headThreshold;

            Location hitLoc = rayTrace.getHitPosition().toLocation(world);

            // Deal damage THROUGH the vanilla combat system with the shooter as the
            // damager, so the kill is properly attributed (killer credit, statistics,
            // advancements, combat tracker) instead of a silent setHealth(0).
            double damage = isHeadshot ? 52.0 : 26.0;
            if (isHeadshot) {
                player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "HEADSHOT!");
            }

            boolean willDie = target.getHealth() - damage <= 0.0;

            // Mark the victim so we can swap in our attributed death message
            if (willDie) {
                recentRifleKills.put(target.getUniqueId(),
                        new RifleKill(player.getName(), System.currentTimeMillis()));
            }

            // Attribute the kill through the combat system.
            target.damage(damage, player);

            // Creative-mode players (and otherwise invulnerable targets) ignore damage(),
            // so force a lethal hit through for them, keeping the rifle effective in
            // creative like it used to be.
            if (willDie && target.isValid() && !target.isDead() && target.getHealth() > 0.0) {
                target.setHealth(0.0);
            }

            // --- KILL EFFECTS ---
            if (target.isDead() || willDie) {
                // A. Spawn Red Firework Explosion Effect
                spawnRedFirework(hitLoc);

                // B. Sound Effects
                world.playSound(hitLoc, Sound.ENTITY_VILLAGER_DEATH, 2.0f, 0.9f);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
            } else {
                world.playSound(hitLoc, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1.0f, 0.5f);
            }
        }
    }

    // --- Replace the death message for players killed by the rifle ---
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        RifleKill kill = consumeRifleKill(victim.getUniqueId());
        if (kill == null)
            return;

        event.setDeathMessage(ChatColor.RED + "\u2620 " + ChatColor.YELLOW + victim.getName()
                + ChatColor.RED + " was cooked by " + ChatColor.GREEN + kill.killerName() + "'s rifle!");
    }

    // --- Announce mob kills (mobs have no vanilla death message) ---
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim instanceof Player)
            return; // handled by onPlayerDeath
        RifleKill kill = consumeRifleKill(victim.getUniqueId());
        if (kill == null)
            return;

        String victimName = victim.getType().name().toLowerCase().replace("_", " ");
        Bukkit.broadcastMessage(ChatColor.RED + "\u2620 " + ChatColor.YELLOW + victimName
                + ChatColor.RED + " was cooked by " + ChatColor.GREEN + kill.killerName() + "'s rifle!");
    }

    // Returns and clears the kill record if this entity was rifle-killed in the
    // last
    // 2 seconds, otherwise null.
    private RifleKill consumeRifleKill(UUID id) {
        RifleKill kill = recentRifleKills.remove(id);
        if (kill == null || (System.currentTimeMillis() - kill.time()) >= 2000L) {
            return null;
        }
        return kill;
    }

    // Spawns and immediately detonates a red firework ball
    private void spawnRedFirework(Location loc) {
        World world = loc.getWorld();
        if (world == null)
            return;

        Firework firework = world.spawn(loc, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();

        FireworkEffect effect = FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL)
                .withColor(Color.RED)
                .withFade(Color.RED)
                .trail(true)
                .flicker(true)
                .build();

        meta.addEffect(effect);
        meta.setPower(0);
        firework.setFireworkMeta(meta);

        // Detonate instantly at target impact location
        firework.detonate();
    }

    private boolean isHuntingRifle(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_HORSE_ARMOR || !item.hasItemMeta())
            return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName().contains("Hunting Rifle");
    }

    public static ItemStack createHuntingRifle() {
        ItemStack item = new ItemStack(Material.IRON_HORSE_ARMOR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Hunting Rifle");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + "Sniper Rifle");
            lore.add(ChatColor.GRAY + "Controls: " + ChatColor.WHITE
                    + "R-Click FOV Zoom | L-Click Fire");
            lore.add(ChatColor.GRAY + "Damage: " + ChatColor.RED + "26.0 (Body) / 52.0 (Head)");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}