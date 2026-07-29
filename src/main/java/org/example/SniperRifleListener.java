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

    private final Plugin plugin;
    private final HashMap<UUID, Long> cooldowns = new HashMap<>();

    public SniperRifleListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (isHuntingRifle(item)) {

            // 1. RIGHT-CLICK: Fire Shot
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                tryShootRifle(player);
            }

            // 2. LEFT-CLICK: Toggle/Trigger FOV Zoom Scope
            else if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
                toggleScopeFOV(player);
            }
        }
    }

    // Toggles/Applies FOV Reduction using Slowness III for 6 seconds (or unscopes if active)
    private void toggleScopeFOV(Player player) {
        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.playSound(player.getLocation(), Sound.ITEM_SPYGLASS_STOP_USING, 1.0f, 1.2f);
        } else {
            // Apply Slowness III (gives ~30% FOV zoom effect) for 120 ticks (6 seconds) -- Note changed amplifier to 5 for more effect
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 5, false, false, false));
            player.playSound(player.getLocation(), Sound.ITEM_SPYGLASS_USE, 1.0f, 1.0f);
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

        // Remove scope FOV effect on firing
        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            player.removePotionEffect(PotionEffectType.SLOWNESS);
        }

        // 2. Hitscan Raytrace (120 Blocks)
        double maxDistance = 120.0;
        RayTraceResult rayTrace = world.rayTrace(
                eyeLoc,
                direction,
                maxDistance,
                org.bukkit.FluidCollisionMode.NEVER,
                true,
                0.6,
                (entity) -> entity instanceof LivingEntity && !entity.getUniqueId().equals(player.getUniqueId())
        );

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
            double targetHeadY = target.getEyeLocation().getY();
            double hitY = rayTrace.getHitPosition().getY();
            boolean isHeadshot = Math.abs(targetHeadY - hitY) < 0.5;

            Location hitLoc = rayTrace.getHitPosition().toLocation(world);
            boolean dead = false;

            if (isHeadshot) {
                target.setHealth(0); // Fatal Kill
                dead = true;
                player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "HEADSHOT!");
            } else {
                // Bodyshot
                if (target.getHealth() <= 10.0) {
                    dead = true;
                }
                target.damage(10.0, player);
            }

            // --- KILL EFFECTS & MESSAGES ---
            if (dead || target.isDead()) {
                // A. Spawn Red Firework Explosion Effect
                spawnRedFirework(hitLoc);

                // B. Broadcast Death Message
                String targetName = (target instanceof Player victimPlayer)
                        ? victimPlayer.getName()
                        : target.getType().name().toLowerCase().replace("_", " ");

                Bukkit.broadcastMessage(ChatColor.RED + "☠ " + ChatColor.YELLOW + targetName
                        + ChatColor.RED + " was cooked by " + ChatColor.GREEN + player.getName() + "'s rifle!");

                // C. Sound Effects
                world.playSound(hitLoc, Sound.ENTITY_VILLAGER_DEATH, 2.0f, 0.9f);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.5f);
            } else {
                world.playSound(hitLoc, Sound.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, 1.0f, 0.5f);
            }
        }
    }

    // Spawns and immediately detonates a red firework ball
    private void spawnRedFirework(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

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
        if (item == null || item.getType() != Material.SPYGLASS || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName().contains("Hunting Rifle");
    }

    public static ItemStack createHuntingRifle() {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Hunting Rifle");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Type: " + ChatColor.WHITE + "Sniper Rifle");
            lore.add(ChatColor.GRAY + "Controls: " + ChatColor.WHITE + "L-Click FOV Zoom | R-Click Fire");
            lore.add(ChatColor.GRAY + "Damage: " + ChatColor.RED + "10.0 (Body) / INSTANT KILL (Head)");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}