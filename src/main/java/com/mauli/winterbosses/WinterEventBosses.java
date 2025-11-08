package com.mauli.winterbosses;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Transformation;

import java.io.File;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class WinterEventBosses extends JavaPlugin implements Listener {

    private FileConfiguration msg, itemsCfg, arenasCfg, bossesCfg;
    private String difficultyLevel;
    private double diffHpMul=1.0, diffDmgMul=1.0;
    private String worldLock;
    private final Map<String, ItemStack> items = new HashMap<>();
    private final Map<String, Arena> arenas = new HashMap<>();
    private final Map<String, BossDef> bosses = new HashMap<>();
    private final Map<UUID, ActiveBoss> activeBosses = new HashMap<>();
    private final Map<String, Long> lastSpawn = new HashMap<>();
    private final Map<String, String> arenaToBoss = new HashMap<>();
    private final Map<UUID, Set<String>> playerInArenas = new HashMap<>();
    private BukkitRunnable tickTask;

    @Override
    public void onEnable() {
        saveResource("messages.yml", false);
        saveResource("items.yml", false);
        saveResource("arenas.yml", false);
        saveResource("bosses.yml", false);
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        startTickTask();
        getLogger().info("WeihnachtBosses enabled (world-lock=" + worldLock + ")");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) tickTask.cancel();
        for (UUID id : new ArrayList<>(activeBosses.keySet())) {
            ActiveBoss ab = activeBosses.remove(id);
            if (ab != null) ab.remove();
        }
        HandlerList.unregisterAll((Listener) this);
    }

    private void loadAll() {
        msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        itemsCfg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "items.yml"));
        arenasCfg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "arenas.yml"));
        bossesCfg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "bosses.yml"));
        worldLock = bossesCfg.getString("settings.world-lock", "Farmwelt");
        difficultyLevel = bossesCfg.getString("settings.difficulty","NORMAL").toUpperCase();
        var dsec = bossesCfg.getConfigurationSection("settings.difficulties."+difficultyLevel);
        if (dsec != null) {
            diffHpMul = dsec.getDouble("health",1.0);
            diffDmgMul = dsec.getDouble("damage",1.0);
        } else { diffHpMul=1.0; diffDmgMul=1.0; }
        loadItems();
        loadArenas();
        loadBosses();
    }

    private String m(String key) { return color(msg.getString(key, key)); }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    private void loadItems() {
        items.clear();
        ConfigurationSection root = itemsCfg.getConfigurationSection("items");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection it = root.getConfigurationSection(id);
            Material mat = Material.matchMaterial(it.getString("material", "STONE"));
            if (mat == null) mat = Material.STONE;
            ItemStack stack = new ItemStack(mat);
            ItemMeta meta = stack.getItemMeta();
            meta.setDisplayName(color(it.getString("name", "&fItem")));
            List<String> lore = new ArrayList<>();
            for (String line : it.getStringList("lore")) lore.add(color(line));
            if (it.contains("model-data")) meta.setCustomModelData(it.getInt("model-data"));
            ConfigurationSection ench = it.getConfigurationSection("enchantments");
            if (ench != null) {
                for (String eKey : ench.getKeys(false)) {
                    Enchantment e = Enchantment.getByName(eKey);
                    if (e != null) meta.addEnchant(e, ench.getInt(eKey,1), true);
                }
            }
            stack.setItemMeta(meta);
            items.put(id.toUpperCase(), stack);
        }
    }

    private void loadArenas() {
        arenas.clear();
        ConfigurationSection root = arenasCfg.getConfigurationSection("arenas");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(id);
            String world = cs.getString("world", "Farmwelt");
            World w = Bukkit.getWorld(world);
            if (w == null) { getLogger().warning("Arena world not loaded: " + world); continue; }
            ConfigurationSection sp = cs.getConfigurationSection("spawn");
            Location loc = new Location(w, sp.getDouble("x"), sp.getDouble("y"), sp.getDouble("z"),
                    (float)sp.getDouble("yaw"), (float)sp.getDouble("pitch"));
            arenas.put(id.toUpperCase(), new Arena(id.toUpperCase(), loc, cs.getInt("radius",30)));
        }
    }

    private void loadBosses() {
        bosses.clear(); arenaToBoss.clear();
        ConfigurationSection root = bossesCfg.getConfigurationSection("bosses");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(id);
            BossDef d = BossDef.fromYaml(id, cs);
            if (d != null) {
                bosses.put(id.toUpperCase(), d);
                arenaToBoss.put(d.arena, d.id);
            }
        }
    }

    private void startTickTask() {
        int checkSec = Math.max(10, bossesCfg.getInt("spawn-scheduler.check-interval-seconds", 30));
        tickTask = new BukkitRunnable() {
            @Override public void run() {
                // Update bars + drive abilities
                Iterator<Map.Entry<UUID, ActiveBoss>> it = activeBosses.entrySet().iterator();
                while (it.hasNext()) {
                    ActiveBoss ab = it.next().getValue();
                    if (ab.entity == null || ab.entity.isDead() || !ab.entity.isValid()) {
                        ab.remove(); it.remove(); continue;
                    }
                    ab.updateBar();
                    ab.tickAbilities();
                }
            }
        };
        tickTask.runTaskTimer(this, checkSec * 20L, 10L); // ability tick every 0.5s
    }

    private boolean canAutoSpawn(BossDef def) {
        Arena a = arenas.get(def.arena);
        if (a == null) return false;
        if (!a.loc.getWorld().getName().equalsIgnoreCase(worldLock)) return false;
        for (ActiveBoss ab : activeBosses.values()) if (ab.def.id.equals(def.id)) return false;
        if (def.windowStart != null && def.windowEnd != null) {
            LocalTime now = LocalTime.now();
            if (now.isBefore(def.windowStart) || now.isAfter(def.windowEnd)) return false;
        }
        long last = lastSpawn.getOrDefault(def.id, 0L);
        long cdMs = parseDuration(def.respawnCooldown).toMillis();
        return System.currentTimeMillis() - last >= cdMs;
    }

    private Duration parseDuration(String s) {
        if (s == null) return Duration.ofMinutes(30);
        s = s.trim().toLowerCase();
        try {
            long num = Long.parseLong(s.substring(0, s.length()-1));
            return switch (s.charAt(s.length()-1)) {
                case 's' -> Duration.ofSeconds(num);
                case 'm' -> Duration.ofMinutes(num);
                case 'h' -> Duration.ofHours(num);
                case 'd' -> Duration.ofDays(num);
                default -> Duration.ofMinutes(30);
            };
        } catch (Exception ignored) {}
        return Duration.ofMinutes(30);
    }

    private ActiveBoss spawnBoss(String bossId, String arenaId) {
        BossDef def = bosses.get(bossId.toUpperCase());
        if (def == null) return null;
        Arena a = arenas.get(arenaId.toUpperCase());
        if (a == null) return null;
        if (!a.loc.getWorld().getName().equalsIgnoreCase(worldLock)) return null;

        LivingEntity le = (LivingEntity) a.loc.getWorld().spawnEntity(a.loc, def.type);
        le.setCustomNameVisible(true);
        le.setCustomName(color(def.displayName));
        try { le.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(def.health*diffHpMul); } catch (Exception ignored){}
        try { le.setHealth(Math.max(1.0, def.health*diffHpMul)); } catch (Exception ignored){}
        try { le.getAttribute(Attribute.GENERIC_ARMOR).setBaseValue(def.armor); } catch (Exception ignored){}
        try { le.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(def.damage*diffDmgMul); } catch (Exception ignored){}
        EntityEquipment eq = le.getEquipment();
        if (eq != null) {
            if (def.equipment.containsKey("HAND")) eq.setItemInMainHand(new ItemStack(def.equipment.get("HAND")));
            if (def.equipment.containsKey("OFF_HAND")) eq.setItemInOffHand(new ItemStack(def.equipment.get("OFF_HAND")));
            if (def.equipment.containsKey("HEAD")) eq.setHelmet(new ItemStack(def.equipment.get("HEAD")));
            if (def.equipment.containsKey("CHEST")) eq.setChestplate(new ItemStack(def.equipment.get("CHEST")));
            if (def.equipment.containsKey("LEGS")) eq.setLeggings(new ItemStack(def.equipment.get("LEGS")));
            if (def.equipment.containsKey("FEET")) eq.setBoots(new ItemStack(def.equipment.get("FEET")));
        }

        // BossBar
        BarColor color = def.barColor == null ? BarColor.WHITE : def.barColor;
        BossBar bar = Bukkit.createBossBar(color(def.displayName + " &7["+difficultyLevel+"]"), color, BarStyle.SEGMENTED_10);
        ActiveBoss ab = new ActiveBoss(def, le, bar, a);
        // Special visuals
        if (def.id.equals("SNOW_QUEEN")) {
            if (le.getEquipment()!=null) le.getEquipment().setChestplate(new ItemStack(Material.ELYTRA));
        }
        if (def.id.equals("ICE_GOLEM")) {
            // Create 4 ice BlockDisplays to scale up the visual silhouette
            World w = le.getWorld();
            for (int i=0;i<4;i++) {
                BlockDisplay bd = w.spawn(le.getLocation(), BlockDisplay.class);
                BlockData data = Material.PACKED_ICE.createBlockData();
                bd.setBlock(data);
                bd.setGlowing(true);
                bd.setBrightness(new Display.Brightness(15, 15));
                ab.displays.add(bd);
            }
        }
        activeBosses.put(le.getUniqueId(), ab);
        lastSpawn.put(def.id, System.currentTimeMillis());

        if (bossesCfg.getBoolean("settings.announce-spawns", true)) {
            String message = m("boss-spawned")
                    .replace("{boss}", color(def.displayName))
                    .replace("{world}", a.loc.getWorld().getName())
                    .replace("{x}", String.format("%.0f", a.loc.getX()))
                    .replace("{y}", String.format("%.0f", a.loc.getY()))
                    .replace("{z}", String.format("%.0f", a.loc.getZ()));
            Bukkit.broadcastMessage(color(msg.getString("prefix","")) + message);
        }

        // Drops & cleanup
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onDeath(EntityDeathEvent e) {
                if (!e.getEntity().getUniqueId().equals(le.getUniqueId())) return;
                ab.remove();
                e.getDrops().clear();
                for (Drop d : def.drops) {
                    if (ThreadLocalRandom.current().nextDouble() <= d.chance) {
                        ItemStack it = items.get(d.itemId.toUpperCase());
                        if (it != null) e.getDrops().add(it.clone());
                    }
                }
                Bukkit.broadcastMessage(color(msg.getString("prefix","")) + m("boss-defeated").replace("{boss}", color(def.displayName)));
                HandlerList.unregisterAll(this);
            }
        }, this);

        return ab;
    }

    // Active boss runtime with abilities
    class ActiveBoss {
        final BossDef def; final LivingEntity entity; final BossBar bar; final Arena arena;
        final Map<String, Long> cds = new HashMap<>();
        final List<Display> displays = new ArrayList<>();
        ActiveBoss(BossDef def, LivingEntity entity, BossBar bar, Arena arena) {
            this.def=def; this.entity=entity; this.bar=bar; this.arena=arena;
            updateBar();
        }
        void updateBar() {
            if (entity == null || entity.isDead()) { remove(); return; }
            double max = Math.max(1.0, entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());
            bar.setProgress(Math.max(0.0, Math.min(1.0, entity.getHealth()/max)));
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (!pl.getWorld().equals(entity.getWorld())) { bar.removePlayer(pl); continue; }
                boolean inRange = pl.getLocation().distanceSquared(entity.getLocation()) <= (64*64);
                boolean inArena = pl.getWorld().equals(arena.loc.getWorld()) && pl.getLocation().distanceSquared(arena.loc) <= (arena.radius*arena.radius);
                if (inRange || inArena) bar.addPlayer(pl); else bar.removePlayer(pl);
            }
        }
        // keep any visual displays following the boss
        void alignDisplays() {
            if (displays.isEmpty()) return;
            Location base = entity.getLocation();
            int i=0;
            for (var d : displays) {
                if (d==null || d.isDead()) continue;
                // simple offsets around entity
                double offX = (i%3 - 1) * 0.8;
                double offY = 0.6 + (i/3)*0.8;
                double offZ = (i%2==0 ? 1 : -1) * 0.6;
                d.teleport(base.clone().add(offX, offY, offZ));
                i++;
            }
            alignDisplays();
        }
        void remove() { bar.removeAll(); bar.setVisible(false); for (var d: displays) if (d!=null && !d.isDead()) d.remove(); displays.clear(); }
        void flashBar(BarColor c) {
            BarColor old = bar.getColor();
            bar.setColor(c);
            Bukkit.getScheduler().runTaskLater(WinterEventBosses.this, () -> bar.setColor(old), 20L);
        }

        void tickAbilities() {
            Player target = nearestPlayer(entity.getLocation(), 18.0);
            if (target == null) return;
            // rotate abilities per boss id
            switch (def.id) {
                case "KRAMPUS" -> {
                    ability("SMASH", 6, () -> groundSmash(entity, 6, 3.0));
                    ability("HELLCHAIN", 10, () -> hellChainPull(entity, target, 1.1));
                }
                case "SNOW_QUEEN" -> {
                    wingsParticles(entity);
                    ability("BLIZZARD", 8, () -> blizzard(entity.getLocation(), 8, 6.0));
                    ability("ICICLE_RAIN", 10, () -> icicleRain(target.getLocation(), 12));
                }
                case "ICE_GOLEM" -> {
                    ability("PRISON", 12, () -> snowPrison(target, 4));
                    ability("SMASH", 7, () -> groundSmash(entity, 7, 3.5));
                }
                case "FROST_WARDEN" -> {
                    ability("FROSTBITE", 7, () -> frostbiteCone(entity, target, 9));
                    ability("LEAP", 9, () -> leapAt(entity, target.getLocation(), 1.1));
                }
                case "YETI" -> {
                    ability("ROAR", 8, () -> roarKnockback(entity, 10));
                    ability("BOULDER", 9, () -> boulderThrow(entity, target));
                }
                case "SANTAS_SHADE" -> {
                    ability("SHADOW_DASH", 7, () -> shadowDashSlash(entity, target));
                    ability("MINIONS", 20, () -> summonShades(entity));
                }
            }
        }

        void wingsParticles(LivingEntity caster) {
            Location c = caster.getLocation().add(0,1.3,0);
            World w = c.getWorld();
            // left and right wing curves using simple parametric loop
            for (double t=0; t<=Math.PI; t+=Math.PI/20) {
                double x = Math.sin(t)*0.8;
                double y = Math.cos(t)*0.4;
                w.spawnParticle(Particle.END_ROD, c.clone().add(-0.4 - x, y, 0), 1, 0,0,0, 0);
                w.spawnParticle(Particle.END_ROD, c.clone().add(0.4 + x, y, 0), 1, 0,0,0, 0);
            }
        }

        void ability(String key, int cdSeconds, Runnable run) {
            long now = System.currentTimeMillis();
            long next = cds.getOrDefault(key, 0L);
            if (now < next) return;
            cds.put(key, now + cdSeconds*1000L);
            run.run();
        }

        // ==== Ability implementations ====
        void groundSmash(LivingEntity caster, int radius, double power) {
            flashBar(BarColor.RED);
            Location c = caster.getLocation();
            c.getWorld().playSound(c, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1, 0.6f);
            // expanding ring
            for (int i=0;i<20;i++) {
                int step = i;
                Bukkit.getScheduler().runTaskLater(WinterEventBosses.this, () -> {
                    double r = (step/20.0)*radius;
                    for (double a=0;a<Math.PI*2;a+=Math.PI/16) {
                        double x = c.getX()+Math.cos(a)*r;
                        double z = c.getZ()+Math.sin(a)*r;
                        c.getWorld().spawnParticle(Particle.BLOCK, new Location(c.getWorld(), x, c.getY(), z), 2, 0,0,0, 0, Material.PACKED_ICE.createBlockData());
                    }
                }, i);
            }
            for (Player p : playersNear(c, radius)) {
                Vector v = p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(power).setY(0.6);
                p.setVelocity(v);
                p.damage(4.0, caster);
            }
            caster.swingMainHand();
        }

        void hellChainPull(LivingEntity caster, Player target, double speed) {
            flashBar(BarColor.PURPLE);
            Location a = caster.getEyeLocation();
            Location b = target.getEyeLocation();
            Vector dir = b.toVector().subtract(a.toVector()).normalize();
            a.getWorld().playSound(a, Sound.ITEM_CHAIN_BREAK, 1, 0.8f);
            for (double t=0; t<=a.distance(b); t+=0.6) {
                Location p = a.clone().add(dir.clone().multiply(t));
                a.getWorld().spawnParticle(Particle.CRIT_MAGIC, p, 4, 0.03,0.03,0.03, 0.01);
            }
            Vector pull = caster.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(speed).setY(0.4);
            target.setVelocity(pull);
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 40, 1, true, true, true));
        }

        void blizzard(Location center, int seconds, double radius) {
            flashBar(BarColor.BLUE);
            World w = center.getWorld();
            w.playSound(center, Sound.WEATHER_RAIN_ABOVE, 1, 0.5f);
            new BukkitRunnable() {
                int t=0;
                @Override public void run() {
                    if (t++ > seconds*2) { cancel(); return; }
                    for (int i=0;i<80;i++) {
                        double r = radius * Math.sqrt(Math.random());
                        double ang = Math.random()*Math.PI*2;
                        double x = center.getX()+Math.cos(ang)*r;
                        double z = center.getZ()+Math.sin(ang)*r;
                        double y = center.getY()+1+Math.random()*2;
                        w.spawnParticle(Particle.SNOWFLAKE, new Location(w,x,y,z), 1, 0,0,0, 0);
                    }
                    for (Player p : playersNear(center, radius)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 40, 2, true, true, true));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 0, true, true, true));
                        p.setFreezeTicks(Math.min(200, p.getFreezeTicks()+10));
                    }
                }
            }.runTaskTimer(WinterEventBosses.this, 0L, 10L);
        }

        void icicleRain(Location target, int shards) {
            flashBar(BarColor.WHITE);
            World w = target.getWorld();
            w.playSound(target, Sound.BLOCK_GLASS_BREAK, 1, 1.2f);
            for (int i=0;i<shards;i++) {
                Location spawn = target.clone().add(ThreadLocalRandom.current().nextDouble(-3,3), 12 + ThreadLocalRandom.current().nextDouble(0,6), ThreadLocalRandom.current().nextDouble(-3,3));
                Snowball sb = w.spawn(spawn, Snowball.class);
                sb.setItem(new ItemStack(Material.ICE));
                Vector v = target.toVector().subtract(spawn.toVector()).normalize().multiply(0.8);
                sb.setVelocity(v);
                sb.setShooter(entity);
                new BukkitRunnable(){
                    int life=0;
                    @Override public void run(){
                        if (sb.isDead() || life++>60) { cancel(); return; }
                        w.spawnParticle(Particle.FALLING_WATER, sb.getLocation(), 2, 0.02,0.02,0.02, 0.01);
                        for (Player p : playersNear(sb.getLocation(), 1.3)) {
                            p.damage(3.0, entity);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 40, 1, true,true,true));
                        }
                    }
                }.runTaskTimer(WinterEventBosses.this, 1L, 1L);
            }
        }

        void snowPrison(Player target, int radius) {
            flashBar(BarColor.WHITE);
            Location c = target.getLocation();
            World w = c.getWorld();
            w.playSound(c, Sound.BLOCK_SNOW_HIT, 1, 1.0f);
            List<Location> cage = new ArrayList<>();
            for (int y=0;y<3;y++) {
                for (int i=0;i<16;i++) {
                    double a = i*(Math.PI*2/16);
                    cage.add(c.clone().add(Math.cos(a)*1.5, y, Math.sin(a)*1.5));
                }
            }
            Set<BlockStateSnapshot> snaps = new HashSet<>();
            for (Location l : cage) {
                var b = l.getBlock();
                snaps.add(new BlockStateSnapshot(b));
                b.setType(Material.PACKED_ICE, false);
            }
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 60, 3, true,true,true));
            target.setFreezeTicks(Math.min(260, target.getFreezeTicks()+120));
            new BukkitRunnable(){ @Override public void run(){ for (BlockStateSnapshot s : snaps) s.restore(); }}.runTaskLater(WinterEventBosses.this, 60L);
        }

        void frostbiteCone(LivingEntity caster, Player target, int range) {
            flashBar(BarColor.BLUE);
            Location eye = caster.getEyeLocation();
            Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
            World w = eye.getWorld();
            for (double t=0;t<=range;t+=0.4) {
                Location p = eye.clone().add(dir.clone().multiply(t));
                w.spawnParticle(Particle.SNOWFLAKE, p, 2, 0.05,0.05,0.05, 0.01);
                for (Player pl : playersNear(p, 1.2)) {
                    pl.damage(2.5, caster);
                    pl.setFreezeTicks(Math.min(200, pl.getFreezeTicks()+8));
                }
            }
            w.playSound(eye, Sound.ENTITY_PLAYER_HURT_FREEZE, 1, 1);
        }

        void leapAt(LivingEntity caster, Location to, double mult) {
            flashBar(BarColor.GREEN);
            Vector v = to.toVector().subtract(caster.getLocation().toVector()).normalize().multiply(mult).setY(0.7);
            caster.setVelocity(v);
            caster.getWorld().playSound(caster.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1, 0.6f);
        }

        void roarKnockback(LivingEntity caster, int radius) {
            flashBar(BarColor.PINK);
            Location c = caster.getLocation();
            c.getWorld().playSound(c, Sound.ENTITY_POLAR_BEAR_WARNING, 1, 0.8f);
            c.getWorld().spawnParticle(Particle.SONIC_BOOM, c, 1, 0,0,0, 0);
            for (Player p : playersNear(c, radius)) {
                Vector v = p.getLocation().toVector().subtract(c.toVector()).normalize().multiply(1.3).setY(0.6);
                p.setVelocity(v);
                p.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, 60, 0, true,true,true));
            }
        }

        void boulderThrow(LivingEntity caster, Player target) {
            flashBar(BarColor.WHITE);
            Location s = caster.getEyeLocation();
            Snowball ball = s.getWorld().spawn(s, Snowball.class);
            ball.setItem(new ItemStack(Material.SNOW_BLOCK));
            ball.setVelocity(target.getEyeLocation().toVector().subtract(s.toVector()).normalize().multiply(1));
            ball.setShooter(caster);
            s.getWorld().playSound(s, Sound.ENTITY_SNOWBALL_THROW, 1, 0.7f);
        }

        void shadowDashSlash(LivingEntity caster, Player target) {
            flashBar(BarColor.RED);
            Location from = caster.getLocation();
            Location to = target.getLocation();
            caster.teleport(to.clone().add(to.getDirection().multiply(-1)));
            caster.swingMainHand();
            to.getWorld().playSound(to, Sound.ENTITY_WITHER_SKELETON_AMBIENT, 1, 0.6f);
            // slash arc
            for (double a=-Math.PI/2; a<=Math.PI/2; a+=Math.PI/16) {
                Vector rot = to.getDirection().clone();
                Vector right = new Vector(-rot.getZ(), 0, rot.getX());
                Location p = to.clone().add(right.multiply(Math.sin(a)*2)).add(0,1,0);
                to.getWorld().spawnParticle(Particle.SWEEP_ATTACK, p, 3, 0.1,0.1,0.1, 0.01);
            }
            for (Player p : playersNear(to, 3.0)) {
                if (p.equals(target)) { p.damage(6.0, caster); p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0)); }
            }
        }

        void summonShades(LivingEntity caster) {
            flashBar(BarColor.PURPLE);
            Location c = caster.getLocation();
            for (int i=0;i<2;i++) {
                WitherSkeleton ws = c.getWorld().spawn(c.clone().add(ThreadLocalRandom.current().nextDouble(-2,2),0,ThreadLocalRandom.current().nextDouble(-2,2)), WitherSkeleton.class);
                ws.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                ws.setCustomName(ChatColor.DARK_GRAY + "Shade");
                ws.setTarget(nearestPlayer(c, 20));
                Bukkit.getScheduler().runTaskLater(WinterEventBosses.this, ws::remove, 20*20L);
            }
        }
    }

    static class BlockStateSnapshot {
        private final Block block;
        private final Material type;
        private final byte data;
        BlockStateSnapshot(Block b) { this.block=b; this.type=b.getType(); this.data = 0; }
        void restore(){ block.setType(type, false); }
    }

    // Helpers
    List<Player> playersNear(Location c, double r) {
        List<Player> list = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= r*r) list.add(p);
        }
        return list;
    }

    Player nearestPlayer(Location c, double r) {
        Player best=null; double d2=r*r;
        for (Player p : c.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(c);
            if (d<=d2 && (best==null || d<best.getLocation().distanceSquared(c))) best=p;
        }
        return best;
    }

    // Arena enter trigger
    @EventHandler public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!p.getWorld().getName().equalsIgnoreCase(worldLock)) return;
        if (e.getFrom().getBlockX()==e.getTo().getBlockX() &&
            e.getFrom().getBlockY()==e.getTo().getBlockY() &&
            e.getFrom().getBlockZ()==e.getTo().getBlockZ()) return;
        Set<String> entered = playerInArenas.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        for (Arena a : arenas.values()) {
            if (!a.loc.getWorld().equals(p.getWorld())) continue;
            double d2 = a.loc.distanceSquared(p.getLocation());
            boolean inside = d2 <= (a.radius * a.radius);
            boolean wasInside = entered.contains(a.id);
            if (inside && !wasInside) {
                entered.add(a.id);
                String bossId = arenaToBoss.get(a.id);
                if (bossId != null) {
                    BossDef def = bosses.get(bossId);
                    if (def != null && canAutoSpawn(def)) {
                        spawnBoss(def.id, def.arena);
                    }
                }
            } else if (!inside && wasInside) {
                entered.remove(a.id);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("Only players."); return true; }
        if (!p.hasPermission("weihnacht.admin")) { p.sendMessage(color(msg.getString("prefix","")+msg.getString("no-permission"))); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            p.sendMessage(color("&b/weihnacht reload &7- Konfig neu laden"));
            p.sendMessage(color("&b/weihnacht spawn <Boss> &7- Boss in seiner Arena spawnen (Farmwelt)"));
            p.sendMessage(color("&b/weihnacht tp <Arena> &7- Zur Arena teleportieren"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> { loadAll(); p.sendMessage(color(msg.getString("prefix","")+msg.getString("reloaded"))); return true; }
            case "spawn" -> {
                if (args.length < 2) { p.sendMessage(color("&c/weihnacht spawn <Boss>")); return true; }
                String b = args[1].toUpperCase();
                BossDef def = bosses.get(b);
                if (def == null) { p.sendMessage(color(msg.getString("prefix","")+msg.getString("boss-not-found"))); return true; }
                if (!p.getWorld().getName().equalsIgnoreCase(worldLock)) { p.sendMessage(color(msg.getString("prefix","")+msg.getString("world-locked"))); return true; }
                ActiveBoss ab = spawnBoss(b, def.arena);
                if (ab == null) { p.sendMessage(color("&cSpawn fehlgeschlagen (Arena/World?).")); }
                return true;
            }
            case "tp" -> {
                if (args.length < 2) { p.sendMessage(color("&c/weihnacht tp <Arena>")); return true; }
                Arena a = arenas.get(args[1].toUpperCase());
                if (a == null) { p.sendMessage(color(msg.getString("prefix","")+msg.getString("arena-not-found"))); return true; }
                p.teleport(a.loc);
                p.sendMessage(color(msg.getString("prefix","")+msg.getString("arena-teleport").replace("{arena}", a.id)));
                return true;
            }
            default -> p.sendMessage(color(msg.getString("prefix","")+msg.getString("unknown-arg")));
        }
    }

    // --- data ---
    static class Drop { final String itemId; final double chance; Drop(String i, double c){ itemId=i; chance=c; } }
    record Arena(String id, Location loc, int radius) {}

    static class BossDef {
        String id, displayName, arena, respawnCooldown;
        EntityType type; double health, armor, damage;
        Map<String,Material> equipment; List<Drop> drops;
        LocalTime windowStart, windowEnd;
        BarColor barColor;
        static BossDef fromYaml(String id, ConfigurationSection cs) {
            try {
                BossDef d = new BossDef();
                d.id = id.toUpperCase();
                d.displayName = cs.getString("display-name","&fBoss");
                d.type = EntityType.valueOf(cs.getString("type","ZOMBIE"));
                d.health = cs.getDouble("health", 200.0);
                d.armor = cs.getDouble("armor", 0.0);
                d.damage = cs.getDouble("damage", 6.0);
                d.arena = cs.getString("arena","FARM_SPAWN").toUpperCase();
                d.equipment = new HashMap<>();
                ConfigurationSection e = cs.getConfigurationSection("equipment");
                if (e!=null) for (String k : e.getKeys(false)) {
                    Material m = Material.matchMaterial(e.getString(k));
                    if (m!=null) d.equipment.put(k.toUpperCase(), m);
                }
                d.drops = new ArrayList<>();
                for (Map<?,?> m : cs.getMapList("drops")) {
                    String item = String.valueOf(m.get("item"));
                    double chance = Double.parseDouble(String.valueOf(m.get("chance")));
                    d.drops.add(new Drop(item, chance));
                }
                String win = cs.getString("window", null);
                if (win!=null && win.contains("-")) {
                    String[] parts = win.split("-");
                    d.windowStart = LocalTime.parse(parts[0]);
                    d.windowEnd = LocalTime.parse(parts[1]);
                }
                d.respawnCooldown = cs.getString("respawn-cooldown","45m");
                try { d.barColor = BarColor.valueOf(cs.getString("bossbar-color","WHITE")); } catch (Exception ex) { d.barColor = BarColor.WHITE; }
                return d;
            } catch (Exception ex) { ex.printStackTrace(); return null; }
        }
    }
}
