package com.kixmc.hostilepassives;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.*;

public class HostilePassives extends JavaPlugin implements Listener {

    Map<Entity, HostilePassive> trackedEntities;
    Map<Player, Entity> lastDamager;
    List<Entity> noSync;
    List<Entity> syncToRemove;
    List<Entity> cleanupToRemove;
    List<EntityType> hostileTypes;

    int checkFrequency;
    int checkDistance;
    int syncFrequency;

    public void onEnable() {
        saveDefaultConfig();
        trackedEntities = new HashMap<>();
        lastDamager = new HashMap<>();
        noSync = new ArrayList<>();
        syncToRemove = new ArrayList<>();
        cleanupToRemove = new ArrayList<>();
        hostileTypes = new ArrayList<>();
        updateConfigVars();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("hp")).setExecutor(this);

        startCheckTask();
        startAITask();
        startExemptTask();
        startCleanupTask();
    }

    public void onDisable() {
        for (HostilePassive hc : trackedEntities.values()) {
            if(hc.getControllerEntity() != null) hc.getControllerEntity().remove();
            ((LivingEntity) hc.getEntity()).setAI(true);
        }
        // the tacks are cancelled automatically
    }

    public void startCheckTask() {
        // hostile passive creation & removal
        // uses the first half of the check distance for the activation radius, and second half for deactivation
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                for (Entity e : p.getNearbyEntities(checkDistance, checkDistance, checkDistance)) {
                    if (!hostileTypes.contains(e.getType())) continue;

                    if (trackedEntities.containsKey(e)) {
                        if (e.getLocation().distance(p.getLocation()) >= Math.round(checkDistance / 2f)) {
                            if (trackedEntities.get(e).getControllerEntity() != null) trackedEntities.get(e).getControllerEntity().remove();
                            trackedEntities.remove(e);
                            ((LivingEntity) e).setAI(true);
                        }
                        continue;
                    }

                    if (e.getLocation().distance(p.getLocation()) >= Math.round(checkDistance / 2f)) continue;
                    trackedEntities.put(e, new HostilePassive(e, null));
                }
            }
        }, 20L, checkFrequency);
    }

    public void startCleanupTask() {
        // in some cases the check task is not able to untrack the entity (ex. player teleports away). this task will clean up any tracked entities that shouldn't be
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for(Entity e : trackedEntities.keySet()) {
                if(e.getNearbyEntities(checkDistance, checkDistance, checkDistance).stream().noneMatch(ent -> ent instanceof Player)) cleanupToRemove.add(e);
            }
            for(Entity e : cleanupToRemove) {
                if(trackedEntities.get(e).getControllerEntity() != null) trackedEntities.get(e).getControllerEntity().remove();
                trackedEntities.remove(e);
                ((LivingEntity) e).setAI(true);
            }
            cleanupToRemove.clear();
        }, 600L, 600L); // 30 seconds
    }

    public void startAITask() {
        // hostile passive "ai"
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {

            for (HostilePassive hp : trackedEntities.values()) {

                // create the controller entity
                if (hp.getControllerEntity() == null) {

                    // we use a silverfish as the controller entity because of their small hitbox and generic hostile behavior
                    Silverfish sf = (Silverfish) hp.getEntity().getWorld().spawnEntity(hp.getEntity().getLocation(), EntityType.SILVERFISH);
                    sf.setInvulnerable(true);
                    sf.setInvisible(true);
                    sf.setSilent(true);
                    sf.setCollidable(false);

                    // silverfish are bad at jumping, this makes their behavior more realistic
                    sf.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 999999, 1, false, false, false));

                    sf.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(((LivingEntity) hp.getEntity()).getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getValue() * getConfig().getDouble("entities." + hp.getEntity().getType().toString().toLowerCase() + ".speed-multiplier"));

                    hp.setControllerEntity(sf);
                }

                // stop tracking if the entity dies
                if (hp.getEntity().isDead()) {
                    hp.getControllerEntity().remove();
                    syncToRemove.add(hp.getEntity());
                    continue;
                }

                // stop tracking if the controller is removed somehow
                if (hp.getControllerEntity().isDead()) {
                    syncToRemove.add(hp.getEntity());
                    continue;
                }

                // don't sync controller entity with entity if !isSynced, or has passengers (ex. horses), or is in a vehicle
                if (!hp.isSynced() || !hp.getEntity().getPassengers().isEmpty() || hp.getEntity().isInsideVehicle()) {
                    // entity gets movement priority
                    ((LivingEntity) hp.getEntity()).setAI(true);
                    ((LivingEntity) hp.getControllerEntity()).setAI(false);
                    hp.getControllerEntity().teleport(hp.getEntity().getLocation());
                    return;
                }
                // controller entity gets movement priority
                ((LivingEntity) hp.getEntity()).setAI(false);
                ((LivingEntity) hp.getControllerEntity()).setAI(true);
                hp.getEntity().teleport(hp.getControllerEntity().getLocation());
            }
            for (Entity e : syncToRemove) {
                trackedEntities.remove(e);
                ((LivingEntity) e).setAI(true);
            }
            syncToRemove.clear();
        }, 20L, syncFrequency);
    }

    public void startExemptTask() {
        // don't target exempt users
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (HostilePassive hp : trackedEntities.values()) {
                if (hp.getControllerEntity() == null) continue;

                if (((Silverfish) hp.getControllerEntity()).getTarget() != null && ((Silverfish) hp.getControllerEntity()).getTarget() instanceof Player) {
                    if (((Silverfish) hp.getControllerEntity()).getTarget().hasPermission(Objects.requireNonNull(getConfig().getString("settings.exempt-all-permission"))) || ((Silverfish) hp.getControllerEntity()).getTarget().hasPermission(Objects.requireNonNull(getConfig().getString("settings.exempt-permission-scheme")).replace("{MOB}", hp.getConfigName())) || getConfig().getStringList("entities." + hp.getConfigName() + ".exempt-uuids").contains(((Silverfish) hp.getControllerEntity()).getTarget().getUniqueId().toString())) {
                        ((Silverfish) hp.getControllerEntity()).setTarget(null);
                    }
                }

            }
        }, 20L, 1L);
    }

    // knockback support
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        for (HostilePassive hp : trackedEntities.values()) {
            if (hp.getControllerEntity() == e.getDamager()) {
                e.setDamage(getConfig().getInt("entities." + hp.getConfigName() + ".damage"));
                if (e.getEntity() instanceof Player) {
                    lastDamager.put((Player) e.getEntity(), hp.getEntity());
                    return;
                }
            }
            if (!(e.getDamager() instanceof Player)) return;
            if (e.getEntity() != hp.getEntity()) return;
            if (!hp.isSynced()) return;
            ((LivingEntity) hp.getEntity()).setAI(true);
            hp.setSynced(false);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                hp.setSynced(true);
                ((LivingEntity) hp.getEntity()).setAI(false);
            }, 25L);
            return;
        }
    }

    // there are some instances where the synced entities hotbox causes them to suffocate, this fixes that
    @EventHandler
    public void onSuffocate(EntityDamageEvent e) {
        if (!trackedEntities.containsKey(e.getEntity())) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (!lastDamager.containsKey(e.getEntity())) return;
        if (!trackedEntities.containsKey(lastDamager.get(e.getEntity()))) return;
        HostilePassive hc = trackedEntities.get(lastDamager.get(e.getEntity()));
        hc.getControllerEntity().remove();
        trackedEntities.remove(hc.getEntity());
        ((LivingEntity) hc.getEntity()).setAI(true);
        e.setDeathMessage("{PLAYER} was slain by {MOB}".replace("{PLAYER}", e.getEntity().getName()).replace("{MOB}", fancifi(hc.getConfigName())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastDamager.remove(e.getPlayer());
    }

    public void updateConfigVars() {
        checkFrequency = getConfig().getInt("settings.check.frequency");
        checkDistance = getConfig().getInt("settings.check.range");
        syncFrequency = getConfig().getInt("settings.sync-frequency");
        for (String et : Objects.requireNonNull(getConfig().getConfigurationSection("entities")).getKeys(false)) hostileTypes.add(EntityType.valueOf(et.toUpperCase()));
    }

    public boolean onCommand(@NonNull CommandSender sender, Command cmd, @NonNull String label, @NonNull String[] args) {
        if (!cmd.getLabel().equalsIgnoreCase("hp")) return true;
        if (!sender.hasPermission("hp.reload")) {
            sender.sendMessage("No permission");
            return true;
        }

        Bukkit.getScheduler().cancelTasks(this);
        for (HostilePassive hc : trackedEntities.values()) {
            hc.getControllerEntity().remove();
            ((LivingEntity) hc.getEntity()).setAI(true);
        }
        trackedEntities.clear();
        hostileTypes.clear();

        reloadConfig();
        updateConfigVars();

        startCheckTask();
        startAITask();
        startExemptTask();
        startCleanupTask();

        sender.sendMessage("HostilePassives successfully reloaded");
        return true;
    }

    public static class HostilePassive {
        private final Entity entity;
        private Entity controllerEntity;
        private boolean synced;
        private final String configName;

        public HostilePassive(Entity ent, Entity ctrlEnt) {
            this.entity = ent;
            this.controllerEntity = ctrlEnt;
            this.synced = true;
            this.configName = ent.getType().toString().toLowerCase();
        }

        public Entity getEntity() {
            return entity;
        }

        public Entity getControllerEntity() {
            return controllerEntity;
        }

        public void setControllerEntity(Entity controllerEntity) {
            this.controllerEntity = controllerEntity;
        }

        public boolean isSynced() {
            return synced;
        }

        public void setSynced(boolean synced) {
            this.synced = synced;
        }

        public String getConfigName() {
            return configName;
        }

    }

    public static String fancifi(String string) {
        char[] chars = string.toLowerCase().toCharArray();
        boolean found = false;
        for (int i = 0; i < chars.length; i++) {
            if (!found && Character.isLetter(chars[i])) {
                chars[i] = Character.toUpperCase(chars[i]);
                found = true;
            } else if (Character.isWhitespace(chars[i])) {
                found = false;
            }
        }
        return String.valueOf(chars);
    }

}