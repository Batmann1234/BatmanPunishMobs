package net.valterion.batmanpunishmobs;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Pose;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.Set;
import java.util.UUID;

public class BatmanPunishMobs extends JavaPlugin implements Listener {

    /** Remitente cuyo comando estamos re-ejecutando (para no interceptarlo de nuevo). */
    private CommandSender bypassing = null;
    /** Jugadores que están siendo castigados (congelados). */
    private final Set<UUID> frozen = new HashSet<>();
    /** Wardens activos, para limpiarlos si se apaga el plugin. */
    private final Set<Warden> wardens = new HashSet<>();
    /** Creepers y blazes de las animaciones sin congelamiento. */
    private final Set<Entity> mobs = new HashSet<>();
    /** Jugadores con una animación con retraso en curso (Ravager), para no lanzarla dos veces. */
    private final Set<UUID> busy = new HashSet<>();
    /** true únicamente mientras nosotros invocamos un mob (para saltarse protecciones de spawn). */
    private boolean spawningMob = false;
    /** Etiqueta de las bolas de fuego inofensivas del Blaze. */
    private static final String FIREBALL_TAG = "bpm_fireball";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Warden w : wardens) {
            if (w.isValid()) w.remove();
        }
        wardens.clear();
        for (Entity m : mobs) {
            if (m.isValid()) m.remove();
        }
        mobs.clear();
        busy.clear();
        for (UUID id : new HashSet<>(frozen)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) release(p);
        }
        frozen.clear();
    }

    // ------------------------------------------------------------------
    // Comando /batmanpunishmobs
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aBatmanPunishMobs recargado.");
            return true;
        }
        // /batmanpunishmobs run <jugador> <comando completo>
        // Sirve para que otros plugins (auto punishers, menús, etc.) lancen la animación.
        if (args.length >= 3 && args[0].equalsIgnoreCase("run")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            String line = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            if (line.startsWith("/")) line = line.substring(1);
            String type = typeOf(line);
            if (target == null || isBusy(target.getUniqueId()) || type == null) {
                dispatch(sender, line);
            } else if (delaysCommand(type)) {
                startAnimation(sender, target, line, type);
            } else {
                dispatch(sender, line);
                playAnimation(type, target);
            }
            return true;
        }
        sender.sendMessage("§cUso: /batmanpunishmobs reload | /batmanpunishmobs run <jugador> <comando>");
        return true;
    }

    // ------------------------------------------------------------------
    // Detección del ban
    // ------------------------------------------------------------------

    private record Match(Player target, String type) {}

    /** Tipo de animación (WARDEN, CREEPER, BLAZE) configurado para el comando, o null si no tiene. */
    private String typeOf(String raw) {
        String line = raw.trim();
        if (line.startsWith("/")) line = line.substring(1);
        String[] args = line.split("\\s+");
        if (args.length == 0 || args[0].isEmpty()) return null;

        String label = args[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        ConfigurationSection sec = getConfig().getConfigurationSection("animations");
        if (sec == null) return null;
        String type = sec.getString(label);
        return type == null ? null : type.trim().toUpperCase(Locale.ROOT);
    }

    /** Devuelve el jugador conectado al que apunta el comando y su animación, o null si no aplica. */
    private Match findTarget(String raw) {
        String line = raw.trim();
        if (line.startsWith("/")) line = line.substring(1);
        String[] args = line.split("\\s+");
        if (args.length < 2) return null;

        String type = typeOf(line);
        if (type == null) return null;

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || isBusy(target.getUniqueId())) return null;
        return new Match(target, type);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (frozen.contains(p.getUniqueId())) { // un jugador congelado no puede usar comandos
            e.setCancelled(true);
            return;
        }
        if (p == bypassing) return;
        if (!p.hasPermission("batmanpunishmobs.use")) return;

        String line = e.getMessage().substring(1).trim();
        Match m = findTarget(line);
        if (m == null) return;

        if (delaysCommand(m.type())) {
            e.setCancelled(true);
            startAnimation(p, m.target(), line, m.type());
        } else {
            playAnimation(m.type(), m.target()); // el comando sigue su curso normal
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        if (!getConfig().getBoolean("intercept-console", true)) return;
        CommandSender s = e.getSender();
        if (s == bypassing) return;
        if (!(s instanceof ConsoleCommandSender) && !(s instanceof RemoteConsoleCommandSender)) return;

        String line = e.getCommand().trim();
        Match m = findTarget(line);
        if (m == null) return;

        if (delaysCommand(m.type())) {
            e.setCancelled(true);
            if (line.startsWith("/")) line = line.substring(1);
            startAnimation(s, m.target(), line, m.type());
        } else {
            playAnimation(m.type(), m.target()); // el comando sigue su curso normal
        }
    }

    // ------------------------------------------------------------------
    // Secuencia del castigo
    // ------------------------------------------------------------------

    private boolean isBusy(UUID id) {
        return frozen.contains(id) || busy.contains(id);
    }

    /** Las animaciones WARDEN y RAVAGER retrasan el comando hasta que termina la secuencia. */
    private boolean delaysCommand(String type) {
        return type.equals("WARDEN") || type.equals("RAVAGER");
    }

    private void startAnimation(CommandSender sender, Player target, String line, String type) {
        switch (type) {
            case "WARDEN" -> punish(sender, target, line);
            case "RAVAGER" -> ravagerAnimation(sender, target, line);
            default -> dispatch(sender, line);
        }
    }

    private void punish(CommandSender sender, Player target, String banCommand) {
        UUID targetId = target.getUniqueId();

        double distance = getConfig().getDouble("warden.distance", 2.5);
        int emerge = getConfig().getInt("warden.emerge-ticks", 60);
        int charge = getConfig().getInt("warden.charge-ticks", 30);
        int banDelay = getConfig().getInt("warden.ban-delay-ticks", 15);
        int leaveDelay = getConfig().getInt("warden.leave-delay-ticks", 20);
        int dig = getConfig().getInt("warden.dig-ticks", 40);

        // 1) El Warden aparece POR DETRÁS del jugador (opuesto a hacia donde mira)
        Location tl = target.getLocation();
        World world = tl.getWorld();

        Vector back = tl.getDirection().setY(0);
        if (back.lengthSquared() < 0.0001) back = new Vector(0, 0, 1);
        back.normalize().multiply(-distance);

        Location spawn = tl.clone().add(back);
        spawn.setDirection(tl.toVector().subtract(spawn.toVector())); // mirando al jugador

        Warden warden = spawnWarden(world, spawn);
        if (warden == null) {
            // No se pudo invocar al Warden (zona protegida, otro plugin, etc.): solo se ejecuta el comando.
            dispatch(sender, banCommand);
            return;
        }
        wardens.add(warden);

        // 2) Congelar al jugador
        frozen.add(targetId);
        target.closeInventory();
        target.leaveVehicle();
        if (getConfig().getBoolean("warden.darkness", true)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 600, 0, false, false));
        }

        world.playSound(spawn, Sound.ENTITY_WARDEN_EMERGE, 2f, 1f);
        world.spawnParticle(Particle.BLOCK, spawn, 60, 1, 0.2, 1, 0, Material.DEEPSLATE.createBlockData());

        // 3) Sale del suelo, ruge y carga el sonic boom
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!warden.isValid()) { abortAndRun(warden, targetId, sender, banCommand); return; }
            warden.setPose(Pose.ROARING, true);
            world.playSound(warden.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 1f);

            // 4) Sonic boom: golpe + partículas, el jugador desaparece
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!warden.isValid()) { abortAndRun(warden, targetId, sender, banCommand); return; }
                warden.setPose(Pose.STANDING, true);
                warden.swingMainHand();

                Player t = Bukkit.getPlayer(targetId);
                if (t != null && t.isOnline()) {
                    sonicBoom(warden, t);
                    t.damage(getConfig().getDouble("warden.damage", 8.0), warden);
                    vanish(t);
                }

                // 5) Se ejecuta el comando real
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    dispatch(sender, banCommand);

                    // Seguro: si el ban no se aplicó (sin permisos, error de sintaxis...), se libera al jugador
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        Player left = Bukkit.getPlayer(targetId);
                        if (left != null && left.isOnline()) release(left);
                    }, 30L);

                    // 6) El Warden se va
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (!warden.isValid()) { abort(warden, targetId); return; }
                        warden.setPose(Pose.DIGGING, true);
                        Location wl = warden.getLocation();
                        world.playSound(wl, Sound.ENTITY_WARDEN_DIG, 2f, 1f);
                        world.spawnParticle(Particle.BLOCK, wl, 60, 1, 0.2, 1, 0, Material.DEEPSLATE.createBlockData());

                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            if (warden.isValid()) {
                                world.spawnParticle(Particle.SCULK_SOUL, warden.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.02);
                                warden.remove();
                            }
                            wardens.remove(warden);
                            frozen.remove(targetId);
                        }, dig);
                    }, leaveDelay);
                }, banDelay);
            }, charge);
        }, emerge);
    }

    /** Línea de partículas de sonic boom desde el Warden hasta el jugador. */
    private void sonicBoom(Warden warden, Player target) {
        World world = warden.getWorld();
        Location from = warden.getLocation().add(0, 1.6, 0);
        Location to = target.getLocation().add(0, 1.0, 0);
        Vector dir = to.toVector().subtract(from.toVector());
        double length = dir.length();
        if (length < 0.01) return;
        dir.normalize();

        for (double i = 1; i < length; i += 1.0) {
            Location point = from.clone().add(dir.clone().multiply(i));
            world.spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);
        }
        world.playSound(from, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 1f);
        world.playSound(to, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 2f, 1f);
    }

    /** Hace desaparecer al jugador para el resto (efecto "baneado"). */
    private void vanish(Player target) {
        Location l = target.getLocation().add(0, 1, 0);
        target.getWorld().spawnParticle(Particle.SCULK_SOUL, l, 40, 0.4, 0.8, 0.4, 0.03);
        target.getWorld().spawnParticle(Particle.POOF, l, 25, 0.4, 0.8, 0.4, 0.05);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != target) other.hidePlayer(this, target);
        }
    }

    /** Deshace el congelamiento y la invisibilidad. */
    private void release(Player p) {
        frozen.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.DARKNESS);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != p) other.showPlayer(this, p);
        }
    }

    /** Intenta invocar un mob. Devuelve null si algo (WorldGuard, otro plugin, el servidor) lo impide. */
    private <T extends Entity> T spawnMob(World world, Location loc, Class<T> type, Consumer<T> init) {
        spawningMob = true;
        try {
            T e = world.spawn(loc, type, init);
            if (e == null) return null;
            if (!e.isValid()) {
                e.remove();
                return null;
            }
            return e;
        } catch (Exception ex) {
            return null;
        } finally {
            spawningMob = false;
        }
    }

    private Warden spawnWarden(World world, Location spawn) {
        return spawnMob(world, spawn, Warden.class, wd -> {
            wd.setAI(false);
            wd.setInvulnerable(true);
            wd.setPersistent(false);
            wd.setRemoveWhenFarAway(true);
            wd.setSilent(true);
            wd.setPose(Pose.EMERGING, true);
        });
    }

    /**
     * WorldGuard (flags mob-spawning / deny-spawn) y otros plugins de protección cancelan el spawn
     * de mobs. Mientras invocamos NUESTROS mobs, deshacemos esa cancelación.
     * Se registra después de WorldGuard (softdepend) y con prioridad HIGHEST para ir al final.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (!spawningMob) return;
        if (!getConfig().getBoolean("bypass-protections", true)) return;
        if (e.isCancelled()) e.setCancelled(false);
    }

    /**
     * WorldGuard (flags invincible / mob-damage) cancela el daño de mobs a jugadores.
     * Solo se deshace la cancelación cuando el daño viene de NUESTRO Warden.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWardenDamage(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (!(d instanceof Warden w && wardens.contains(w)) && !mobs.contains(d)) return;
        if (!(e.getEntity() instanceof Player)) return;
        if (!getConfig().getBoolean("bypass-protections", true)) return;
        if (e.isCancelled()) e.setCancelled(false);
    }

    // ------------------------------------------------------------------
    // Animaciones sin congelamiento (Creeper / Blaze)
    // ------------------------------------------------------------------

    private void playAnimation(String type, Player target) {
        switch (type) {
            case "CREEPER" -> creeperAnimation(target);
            case "BLAZE" -> blazeAnimation(target);
            default -> { }
        }
    }

    /** Ubicación a `dist` bloques delante del jugador (y `height` más arriba), mirando hacia él. */
    private Location inFrontOf(Player target, double dist, double height) {
        Location tl = target.getLocation();
        Vector fwd = tl.getDirection().setY(0);
        if (fwd.lengthSquared() < 0.0001) fwd = new Vector(0, 0, 1);
        fwd.normalize().multiply(dist);
        Location loc = tl.clone().add(fwd).add(0, height, 0);
        loc.setDirection(tl.toVector().subtract(loc.toVector()));
        return loc;
    }

    /** /warn: un Creeper aparece, se hincha y hace una explosión falsa (sin daño ni bloques). */
    private void creeperAnimation(Player target) {
        int fuse = Math.max(1, getConfig().getInt("creeper.fuse-ticks", 10));
        World world = target.getWorld();
        Location spawn = inFrontOf(target, getConfig().getDouble("creeper.distance", 2.0), 0);

        Creeper creeper = spawnMob(world, spawn, Creeper.class, c -> {
            c.setAI(false);
            c.setInvulnerable(true);
            c.setPersistent(false);
            c.setRemoveWhenFarAway(true);
            c.setMaxFuseTicks(fuse);
            c.setIgnited(true);
        });
        if (creeper == null) return; // no se pudo invocar: el comando sigue normal

        mobs.add(creeper);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Location l = creeper.getLocation().add(0, 1, 0);
            if (creeper.isValid()) creeper.remove();
            mobs.remove(creeper);
            // Explosión solo visual y sonora: no daña a nadie ni rompe bloques
            world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 1);
            world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 4f, 1f);
        }, fuse);
    }

    /** /mute: un Blaze aparece y dispara muchas bolas de fuego hacia el jugador (sin congelarlo). */
    private void blazeAnimation(Player target) {
        int duration = getConfig().getInt("blaze.duration-ticks", 60);
        int interval = Math.max(1, getConfig().getInt("blaze.fire-interval-ticks", 3));
        double spread = getConfig().getDouble("blaze.spread", 0.7);
        boolean harmless = getConfig().getBoolean("blaze.harmless", true);
        World world = target.getWorld();
        Location spawn = inFrontOf(target, getConfig().getDouble("blaze.distance", 3.5),
                getConfig().getDouble("blaze.height", 1.5));

        Blaze blaze = spawnMob(world, spawn, Blaze.class, b -> {
            b.setAI(false);
            b.setGravity(false);
            b.setInvulnerable(true);
            b.setPersistent(false);
            b.setRemoveWhenFarAway(true);
        });
        if (blaze == null) return; // no se pudo invocar: el comando sigue normal

        mobs.add(blaze);
        UUID targetId = target.getUniqueId();

        new BukkitRunnable() {
            int elapsed = 0;

            @Override
            public void run() {
                Player t = Bukkit.getPlayer(targetId);
                if (!blaze.isValid() || t == null || !t.isOnline()
                        || !t.getWorld().equals(blaze.getWorld()) || elapsed >= duration) {
                    finish();
                    cancel();
                    return;
                }
                elapsed += interval;

                ThreadLocalRandom r = ThreadLocalRandom.current();
                Location from = blaze.getEyeLocation();
                Location aim = t.getEyeLocation().add(
                        r.nextDouble(-spread, spread), r.nextDouble(-spread, spread), r.nextDouble(-spread, spread));
                Vector dir = aim.toVector().subtract(from.toVector());
                if (dir.lengthSquared() < 0.0001) return;
                dir.normalize();

                Location look = blaze.getLocation();
                look.setDirection(dir);
                blaze.setRotation(look.getYaw(), look.getPitch());

                SmallFireball fb = blaze.launchProjectile(SmallFireball.class, dir);
                fb.setDirection(dir);
                fb.setIsIncendiary(false);
                fb.setYield(0f);
                if (harmless) fb.addScoreboardTag(FIREBALL_TAG);
                blaze.getWorld().playSound(from, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f);
            }

            private void finish() {
                if (blaze.isValid()) {
                    blaze.getWorld().spawnParticle(Particle.SMOKE, blaze.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.02);
                    blaze.remove();
                }
                mobs.remove(blaze);
            }
        }.runTaskTimer(this, 0L, interval);
    }

    /** El Creeper de /warn nunca explota de verdad: la explosión es solo visual (ver creeperAnimation). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreeperPrime(ExplosionPrimeEvent e) {
        if (mobs.contains(e.getEntity())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // Ravager (/kick): aparece detrás, corre hacia el jugador, lo golpea y se ejecuta el comando
    // ------------------------------------------------------------------

    private void ravagerAnimation(CommandSender sender, Player target, String command) {
        double startDist = getConfig().getDouble("ravager.start-distance", 12.0);
        double speed = getConfig().getDouble("ravager.speed", 0.6);
        double hitDist = getConfig().getDouble("ravager.hit-distance", 2.0);
        double damage = getConfig().getDouble("ravager.damage", 4.0);
        double knockback = getConfig().getDouble("ravager.knockback", 1.2);
        int cmdDelay = getConfig().getInt("ravager.command-delay-ticks", 5);
        int maxTicks = getConfig().getInt("ravager.max-ticks", 120);
        UUID targetId = target.getUniqueId();
        World world = target.getWorld();

        // Detrás del jugador: el punto más lejano (hasta startDist) con espacio libre para el Ravager
        Location tl = target.getLocation();
        Vector back = tl.getDirection().setY(0);
        if (back.lengthSquared() < 0.0001) back = new Vector(0, 0, 1);
        back.normalize().multiply(-1);

        Location spawn = tl.clone().add(back.clone().multiply(3));
        for (double d = startDist; d >= 3; d -= 1) {
            Location c = tl.clone().add(back.clone().multiply(d));
            if (c.getBlock().isPassable()
                    && c.clone().add(0, 1, 0).getBlock().isPassable()
                    && c.clone().add(0, 2, 0).getBlock().isPassable()) {
                spawn = c;
                break;
            }
        }
        spawn.setDirection(tl.toVector().subtract(spawn.toVector()));

        Ravager ravager = spawnMob(world, spawn, Ravager.class, r -> {
            r.setAI(false);
            r.setInvulnerable(true);
            r.setPersistent(false);
            r.setRemoveWhenFarAway(true);
            r.setSilent(true);
        });
        if (ravager == null) { // no se pudo invocar: solo el comando
            dispatch(sender, command);
            return;
        }

        mobs.add(ravager);
        busy.add(targetId);
        world.playSound(spawn, Sound.ENTITY_RAVAGER_ROAR, 2f, 1f);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                Player t = Bukkit.getPlayer(targetId);
                if (!ravager.isValid() || t == null || !t.isOnline()
                        || !t.getWorld().equals(ravager.getWorld()) || ticks++ >= maxTicks) {
                    end(); // algo falló: se libera y se ejecuta el comando igualmente
                    return;
                }

                Location rl = ravager.getLocation();
                Location pl = t.getLocation();
                Vector flat = pl.toVector().subtract(rl.toVector()).setY(0);
                double dist = flat.length();

                if (dist <= hitDist) { // ¡te toca!
                    Vector dir = dist < 0.0001 ? new Vector(0, 0, 1) : flat.clone().normalize();
                    double dmg = Math.min(damage, t.getHealth() - 1); // el golpe nunca mata
                    if (dmg > 0) t.damage(dmg, ravager);
                    t.setVelocity(dir.multiply(knockback).setY(0.45));
                    ravager.getWorld().playSound(rl, Sound.ENTITY_RAVAGER_ATTACK, 2f, 1f);
                    ravager.getWorld().spawnParticle(Particle.CRIT, t.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.2);
                    cancel();
                    Bukkit.getScheduler().runTaskLater(BatmanPunishMobs.this, this::end, Math.max(0, cmdDelay));
                    return;
                }

                // Corre hacia el jugador (la altura sigue suavemente a la del jugador)
                Vector step = flat.normalize().multiply(Math.min(speed, dist));
                Location next = rl.clone().add(step);
                next.setY(rl.getY() + (pl.getY() - rl.getY()) * 0.3);
                next.setDirection(step);
                ravager.teleport(next);
                if (ticks % 4 == 0) ravager.getWorld().playSound(next, Sound.ENTITY_RAVAGER_STEP, 1.5f, 1f);
                ravager.getWorld().spawnParticle(Particle.CLOUD, next, 2, 0.5, 0.1, 0.5, 0.01);
            }

            private boolean finished = false;

            private void end() {
                if (finished) return;
                finished = true;
                cancel();
                if (ravager.isValid()) {
                    ravager.getWorld().spawnParticle(Particle.POOF, ravager.getLocation().add(0, 1, 0), 20, 0.5, 0.7, 0.5, 0.02);
                    ravager.remove();
                }
                mobs.remove(ravager);
                busy.remove(targetId);
                dispatch(sender, command);
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    /** Las bolas de fuego del Blaze se apagan al impactar: sin fuego, sin daño. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFireballHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof SmallFireball fb)) return;
        if (!fb.getScoreboardTags().contains(FIREBALL_TAG)) return;
        e.setCancelled(true);
        Location l = fb.getLocation();
        fb.remove();
        l.getWorld().spawnParticle(Particle.FLAME, l, 12, 0.2, 0.2, 0.2, 0.02);
    }

    // ------------------------------------------------------------------

    /** El Warden desapareció antes de tiempo: se libera al jugador y se ejecuta el comando igualmente. */
    private void abortAndRun(Warden warden, UUID targetId, CommandSender sender, String command) {
        abort(warden, targetId);
        dispatch(sender, command);
    }

    private void abort(Warden warden, UUID targetId) {
        wardens.remove(warden);
        Player p = Bukkit.getPlayer(targetId);
        if (p != null) release(p);
        frozen.remove(targetId);
    }

    /** Ejecuta el comando real sin que el plugin lo vuelva a interceptar. */
    private void dispatch(CommandSender sender, String command) {
        CommandSender actual = (sender instanceof Player p && !p.isOnline())
                ? Bukkit.getConsoleSender() : sender;
        CommandSender previous = bypassing;
        bypassing = actual;
        try {
            Bukkit.dispatchCommand(actual, command);
        } finally {
            bypassing = previous;
        }
    }

    // ------------------------------------------------------------------
    // Congelamiento: el jugador no puede hacer nada
    // ------------------------------------------------------------------

    private boolean isFrozen(Player p) {
        return !frozen.isEmpty() && frozen.contains(p.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && isFrozen(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && isFrozen(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInvOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && isFrozen(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isFrozen(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Si se desconecta durante la animación, la secuencia sigue y el ban se ejecuta igual.
        // Al volver a entrar ya no estará congelado.
        frozen.remove(e.getPlayer().getUniqueId());
    }
}
