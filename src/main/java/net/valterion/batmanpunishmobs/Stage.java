package net.valterion.batmanpunishmobs;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * Escenario animado hecho con ItemDisplay nativos de Paper (no usa MythicMobs).
 *
 * Marco local de coordenadas: +X = derecha del jugador que mira la escena, +Y = arriba,
 * +Z = hacia el jugador (el origen mira hacia el jugador). Todo se programa por ticks:
 * se cambia el estado de una pieza (pos / size / rot) y se llama a key(tick, duración),
 * que congela ese estado y lo aplica en ese tick con interpolación en el cliente.
 */
final class Stage {

    private static final float HIDDEN = 0.001f;

    private final BatmanPunishMobs plugin;
    private final Set<Stage> registry;
    final World world;
    final Location origin;

    private final List<Entity> entities = new ArrayList<>();
    private final List<Runnable> closers = new ArrayList<>();
    private final TreeMap<Integer, List<Runnable>> timeline = new TreeMap<>();
    private BukkitTask task;
    private int tick;
    private boolean closed;

    Stage(BatmanPunishMobs plugin, Set<Stage> registry, Location origin) {
        this.plugin = plugin;
        this.registry = registry;
        this.origin = origin;
        this.world = origin.getWorld();
        registry.add(this);
    }

    // ------------------------------------------------------------------
    // Utilidades de colocación
    // ------------------------------------------------------------------

    /** Punto a `dist` bloques delante del jugador (a su altura de suelo), mirando hacia él. */
    static Location frontOf(Player p, double dist) {
        Location pl = p.getLocation();
        Vector f = pl.getDirection().setY(0);
        if (f.lengthSquared() < 0.0001) f = new Vector(0, 0, 1);
        f.normalize();
        Location o = pl.clone();
        if (!p.isOnGround()) o.setY(groundY(pl));
        o.add(f.clone().multiply(dist));
        o.setDirection(f.clone().multiply(-1));
        return o;
    }

    /** Altura del suelo bajo una posición (máx. 8 bloques hacia abajo). */
    static double groundY(Location l) {
        World w = l.getWorld();
        int y = l.getBlockY();
        for (int i = 0; i < 8; i++) {
            Block b = w.getBlockAt(l.getBlockX(), y - i, l.getBlockZ());
            if (!b.isPassable()) return y - i + 1;
        }
        return l.getY();
    }

    /** Convierte coordenadas locales (derecha, arriba, hacia el jugador) a mundo. */
    Location loc(double x, double y, double z) {
        double yaw = Math.toRadians(origin.getYaw());
        double wx = x * Math.cos(yaw) - z * Math.sin(yaw);
        double wz = x * Math.sin(yaw) + z * Math.cos(yaw);
        return new Location(world, origin.getX() + wx, origin.getY() + y, origin.getZ() + wz);
    }

    // ------------------------------------------------------------------
    // Línea de tiempo
    // ------------------------------------------------------------------

    Stage at(int t, Runnable r) {
        timeline.computeIfAbsent(Math.max(0, t), k -> new ArrayList<>()).add(r);
        return this;
    }

    /** Ejecuta r en cada tick entre from y to (ambos incluidos). */
    Stage every(int from, int to, IntConsumer r) {
        for (int t = from; t <= to; t++) {
            final int tk = t;
            at(t, () -> r.accept(tk));
        }
        return this;
    }

    void sound(int t, Sound s, float vol, float pitch) {
        at(t, () -> world.playSound(loc(0, 2, 0), s, vol, pitch));
    }

    void particles(int t, Particle p, double x, double y, double z, int count,
                   double dx, double dy, double dz, double speed) {
        at(t, () -> world.spawnParticle(p, loc(x, y, z), count, dx, dy, dz, speed));
    }

    <T> void particles(int t, Particle p, double x, double y, double z, int count,
                       double dx, double dy, double dz, double speed, T data) {
        at(t, () -> world.spawnParticle(p, loc(x, y, z), count, dx, dy, dz, speed, data));
    }

    /** Anillo de partículas en el plano XY (de cara al jugador). */
    void ringXY(int t, Particle p, double cx, double cy, double cz, double r, int count) {
        at(t, () -> {
            for (int i = 0; i < count; i++) {
                double a = i * 2 * Math.PI / count;
                world.spawnParticle(p, loc(cx + r * Math.cos(a), cy + r * Math.sin(a), cz), 1, 0, 0, 0, 0);
            }
        });
    }

    /** Anillo de partículas horizontal. */
    void ringXZ(int t, Particle p, double cx, double cy, double cz, double r, int count) {
        at(t, () -> {
            for (int i = 0; i < count; i++) {
                double a = i * 2 * Math.PI / count;
                world.spawnParticle(p, loc(cx + r * Math.cos(a), cy, cz + r * Math.sin(a)), 1, 0, 0, 0, 0);
            }
        });
    }

    void adopt(Entity e) {
        entities.add(e);
    }

    void onClose(Runnable r) {
        closers.add(r);
    }

    /** Arranca la escena; al llegar a endTick se limpia todo y se llama a onEnd. */
    void start(int endTick, Runnable onEnd) {
        task = new BukkitRunnable() {
            @Override
            public void run() {
                if (closed) {
                    cancel();
                    return;
                }
                List<Runnable> now = timeline.remove(tick);
                if (now != null) {
                    for (Runnable r : now) {
                        try {
                            r.run();
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Error en la animación (tick " + tick + "): " + ex);
                        }
                    }
                }
                if (tick >= endTick) {
                    cancel();
                    close();
                    if (onEnd != null) onEnd.run();
                    return;
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    void close() {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel();
        for (Runnable r : closers) {
            try {
                r.run();
            } catch (Exception ignored) {
            }
        }
        for (Entity e : entities) {
            if (e != null && e.isValid()) e.remove();
        }
        entities.clear();
        timeline.clear();
        registry.remove(this);
    }

    // ------------------------------------------------------------------
    // Piezas
    // ------------------------------------------------------------------

    Part part(Material m) {
        ItemDisplay d = world.spawn(origin, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(m));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(2f);
            e.setShadowRadius(0f);
            e.setPersistent(false);
            e.setInterpolationDuration(0);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(HIDDEN, HIDDEN, HIDDEN), new Quaternionf()));
        });
        entities.add(d);
        return new Part(d);
    }

    /** Una pieza (ItemDisplay) con estado editable; key() lo fija en un tick. */
    final class Part {
        final ItemDisplay display;
        private final Vector3f pos = new Vector3f();
        private final Vector3f scl = new Vector3f(1, 1, 1);
        private final Quaternionf rot = new Quaternionf();

        private Part(ItemDisplay d) {
            this.display = d;
        }

        Part pos(double x, double y, double z) {
            pos.set((float) x, (float) y, (float) z);
            return this;
        }

        Part move(double dx, double dy, double dz) {
            pos.add((float) dx, (float) dy, (float) dz);
            return this;
        }

        Part size(double x, double y, double z) {
            scl.set((float) x, (float) y, (float) z);
            return this;
        }

        Part size(double s) {
            return size(s, s, s);
        }

        Part hide() {
            scl.set(HIDDEN, HIDDEN, HIDDEN);
            return this;
        }

        Part rotX(double deg) {
            rot.identity().rotateX((float) Math.toRadians(deg));
            return this;
        }

        Part rotY(double deg) {
            rot.identity().rotateY((float) Math.toRadians(deg));
            return this;
        }

        Part rotZ(double deg) {
            rot.identity().rotateZ((float) Math.toRadians(deg));
            return this;
        }

        /** Rotación por ejes (grados), aplicada en orden Y, X, Z. */
        Part rotYXZ(double xDeg, double yDeg, double zDeg) {
            rot.identity().rotateYXZ((float) Math.toRadians(yDeg), (float) Math.toRadians(xDeg),
                    (float) Math.toRadians(zDeg));
            return this;
        }

        Part rot(Quaternionf q) {
            rot.set(q);
            return this;
        }

        Part billboard(Display.Billboard b) {
            display.setBillboard(b);
            return this;
        }

        Part glow(Color c) {
            display.setGlowing(true);
            display.setGlowColorOverride(c);
            return this;
        }

        /** Fija el estado actual en el tick dado, con interpolación de `dur` ticks. */
        Part key(int at, int dur) {
            final Vector3f p = new Vector3f(pos);
            final Vector3f s = new Vector3f(scl);
            final Quaternionf q = new Quaternionf(rot);
            Stage.this.at(at, () -> {
                if (!display.isValid()) return;
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(Math.max(0, dur));
                display.setTransformation(new Transformation(p, q, s, new Quaternionf()));
            });
            return this;
        }
    }
}
