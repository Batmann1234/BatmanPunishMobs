package net.valterion.banwarden;

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
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class BanWarden extends JavaPlugin implements Listener {

    /** Remitente cuyo comando estamos re-ejecutando (para no interceptarlo de nuevo). */
    private CommandSender bypassing = null;
    /** Jugadores que están siendo castigados (congelados). */
    private final Set<UUID> frozen = new HashSet<>();
    /** Wardens activos, para limpiarlos si se apaga el plugin. */
    private final Set<Warden> wardens = new HashSet<>();

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
        for (UUID id : new HashSet<>(frozen)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) release(p);
        }
        frozen.clear();
    }

    // ------------------------------------------------------------------
    // Comando /banwarden
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aBanWarden recargado.");
            return true;
        }
        // /banwarden run <jugador> <comando completo>
        // Sirve para que otros plugins (auto punishers, menús, etc.) lancen la animación.
        if (args.length >= 3 && args[0].equalsIgnoreCase("run")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            String line = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            if (line.startsWith("/")) line = line.substring(1);
            if (target == null || frozen.contains(target.getUniqueId())) {
                dispatch(sender, line);
            } else {
                punish(sender, target, line);
            }
            return true;
        }
        sender.sendMessage("§cUso: /banwarden reload | /banwarden run <jugador> <comando>");
        return true;
    }

    // ------------------------------------------------------------------
    // Detección del ban
    // ------------------------------------------------------------------

    /** Devuelve el jugador conectado al que apunta el comando de ban, o null si no aplica. */
    private Player findTarget(String raw) {
        String line = raw.trim();
        if (line.startsWith("/")) line = line.substring(1);
        String[] args = line.split("\\s+");
        if (args.length < 2) return null;

        String label = args[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!getConfig().getStringList("commands").contains(label)) return null;

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || frozen.contains(target.getUniqueId())) return null;
        return target;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (frozen.contains(p.getUniqueId())) { // un jugador congelado no puede usar comandos
            e.setCancelled(true);
            return;
        }
        if (p == bypassing) return;
        if (!p.hasPermission("banwarden.use")) return;

        String line = e.getMessage().substring(1).trim();
        Player target = findTarget(line);
        if (target == null) return;

        e.setCancelled(true);
        punish(p, target, line);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        if (!getConfig().getBoolean("intercept-console", true)) return;
        CommandSender s = e.getSender();
        if (s == bypassing) return;
        if (!(s instanceof ConsoleCommandSender) && !(s instanceof RemoteConsoleCommandSender)) return;

        String line = e.getCommand().trim();
        Player target = findTarget(line);
        if (target == null) return;

        e.setCancelled(true);
        if (line.startsWith("/")) line = line.substring(1);
        punish(s, target, line);
    }

    // ------------------------------------------------------------------
    // Secuencia del castigo
    // ------------------------------------------------------------------

    private void punish(CommandSender sender, Player target, String banCommand) {
        UUID targetId = target.getUniqueId();

        // 1) Congelar al jugador
        frozen.add(targetId);
        target.closeInventory();
        target.leaveVehicle();
        if (getConfig().getBoolean("darkness", true)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 600, 0, false, false));
        }

        double distance = getConfig().getDouble("distance", 2.5);
        int emerge = getConfig().getInt("emerge-ticks", 60);
        int charge = getConfig().getInt("charge-ticks", 30);
        int banDelay = getConfig().getInt("ban-delay-ticks", 15);
        int leaveDelay = getConfig().getInt("leave-delay-ticks", 20);
        int dig = getConfig().getInt("dig-ticks", 40);

        // 2) El Warden aparece POR DETRÁS del jugador (opuesto a hacia donde mira)
        Location tl = target.getLocation();
        World world = tl.getWorld();

        Vector back = tl.getDirection().setY(0);
        if (back.lengthSquared() < 0.0001) back = new Vector(0, 0, 1);
        back.normalize().multiply(-distance);

        Location spawn = tl.clone().add(back);
        spawn.setDirection(tl.toVector().subtract(spawn.toVector())); // mirando al jugador

        Warden warden = world.spawn(spawn, Warden.class, wd -> {
            wd.setAI(false);
            wd.setInvulnerable(true);
            wd.setPersistent(false);
            wd.setRemoveWhenFarAway(true);
            wd.setSilent(true);
            wd.setPose(Pose.EMERGING, true);
        });
        wardens.add(warden);

        world.playSound(spawn, Sound.ENTITY_WARDEN_EMERGE, 2f, 1f);
        world.spawnParticle(Particle.BLOCK, spawn, 60, 1, 0.2, 1, 0, Material.DEEPSLATE.createBlockData());

        // 3) Sale del suelo, ruge y carga el sonic boom
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!warden.isValid()) { abort(warden, targetId); return; }
            warden.setPose(Pose.ROARING, true);
            world.playSound(warden.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 1f);

            // 4) Sonic boom: golpe + partículas, el jugador desaparece
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!warden.isValid()) { abort(warden, targetId); return; }
                warden.setPose(Pose.STANDING, true);
                warden.swingMainHand();

                Player t = Bukkit.getPlayer(targetId);
                if (t != null && t.isOnline()) {
                    sonicBoom(warden, t);
                    t.damage(getConfig().getDouble("damage", 8.0), warden);
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
