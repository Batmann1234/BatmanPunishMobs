package net.valterion.batmanpunishmobs;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spellcaster;
import org.bukkit.entity.Vex;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 10 animaciones de castigo hechas con ItemDisplay nativos (sin MythicMobs) y, en algunas, mobs reales.
 *
 *   JACK_BOX      payaso que sale de una caja sorpresa
 *   JAWS          mandíbulas gigantes que se cierran sobre el jugador
 *   CLOCK         reloj gigante con cuenta atrás y campanadas
 *   BELL          campana gigante que se balancea y se estrella
 *   BLIZZARD      copo de nieve gigante, estacas y bloque de hielo que se rompe
 *   ANVIL         yunque estilo dibujos animados con sombra en el suelo
 *   BLACKHOLE     agujero negro con escombros orbitando
 *   VEX_SWARM     enjambre de Vex en espiral sobre un círculo ritual
 *   EVOKER_FANGS  Evoker que cierra una jaula de colmillos
 *   GIANT_PUNT    Gigante que lanza al jugador por los aires
 */
final class ShowAnimations {

    /** Etiqueta de los colmillos del Evoker (para anular su daño). */
    static final String FANG_TAG = "bpm_fang";

    /** Lo que la animación le puede pedir al plugin. */
    record Hooks(Runnable impact, Runnable vanish, Runnable done) {}

    @FunctionalInterface
    private interface Show {
        boolean play(Player target, Hooks h);
    }

    private final BatmanPunishMobs plugin;
    private final Map<String, Show> shows = new LinkedHashMap<>();
    private final Set<Stage> active = new HashSet<>();

    ShowAnimations(BatmanPunishMobs plugin) {
        this.plugin = plugin;
        shows.put("JACK_BOX", this::jackBox);
        shows.put("JAWS", this::jaws);
        shows.put("CLOCK", this::clock);
        shows.put("BELL", this::bell);
        shows.put("BLIZZARD", this::blizzard);
        shows.put("ANVIL", this::anvil);
        shows.put("BLACKHOLE", this::blackhole);
        shows.put("VEX_SWARM", this::vexSwarm);
        shows.put("EVOKER_FANGS", this::evokerFangs);
        shows.put("GIANT_PUNT", this::giantPunt);
    }

    boolean has(String type) {
        return type != null && shows.containsKey(type);
    }

    List<String> names() {
        return new ArrayList<>(shows.keySet());
    }

    /** El Gigante necesita que el jugador pueda salir volando, así que no se le congela. */
    boolean freezes(String type) {
        return !"GIANT_PUNT".equals(type);
    }

    boolean play(String type, Player target, Hooks h) {
        Show s = shows.get(type);
        if (s == null) return false;
        Set<Stage> before = new HashSet<>(active);
        try {
            return s.play(target, h);
        } catch (Exception ex) {
            plugin.getLogger().warning("No se pudo iniciar la animación " + type + ": " + ex);
            for (Stage st : new HashSet<>(active)) {
                if (!before.contains(st)) st.close();
            }
            return false;
        }
    }

    void shutdown() {
        for (Stage st : new HashSet<>(active)) st.close();
        active.clear();
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private Stage stage(Location origin) {
        return new Stage(plugin, active, origin);
    }

    private void launch(Stage s, int end, Hooks h) {
        s.start(end, h.done());
    }

    private static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }

    private static BlockData groundData(Location l) {
        Block b = l.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY()) - 1, l.getBlockZ());
        return b.getType().isSolid() ? b.getBlockData() : Material.DIRT.createBlockData();
    }

    private void confetti(Stage s, int t, double x, double y, double z, int n) {
        s.at(t, () -> {
            for (int i = 0; i < n; i++) {
                Color c = Color.fromRGB(rnd().nextInt(256), rnd().nextInt(256), rnd().nextInt(256));
                Location l = s.loc(x + rnd().nextDouble(-1.2, 1.2), y + rnd().nextDouble(-1.2, 1.2),
                        z + rnd().nextDouble(-1.2, 1.2));
                s.world.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, new Particle.DustOptions(c, 1.6f));
            }
        });
    }

    private <T extends Entity> T mob(Stage s, Location loc, Class<T> cls, Consumer<T> init) {
        T e = plugin.spawnMob(loc.getWorld(), loc, cls, init);
        if (e != null) {
            s.adopt(e);
            plugin.trackMob(e);
            s.onClose(() -> plugin.untrackMob(e));
        }
        return e;
    }

    /** Círculo ritual en el suelo: estrella de 6 puntas que gira y gemas orbitando. */
    private void ritual(Stage s, Material bar, Material gem, double r, int from, int to) {
        Stage.Part[] bars = new Stage.Part[3];
        for (int i = 0; i < 3; i++) {
            bars[i] = s.part(bar).pos(0, .06, 0).size(r * 2, .08, .35).rotY(i * 60);
        }
        Stage.Part[] gems = new Stage.Part[12];
        for (int k = 0; k < 12; k++) {
            double a = Math.toRadians(k * 30);
            gems[k] = s.part(gem).size(.45).pos(r * Math.cos(a), .25, r * Math.sin(a));
        }
        for (Stage.Part p : bars) p.key(from, 6);
        for (Stage.Part p : gems) p.key(from, 6);
        int steps = (to - from) / 4;
        for (int st = 1; st <= steps; st++) {
            int tk = from + st * 4;
            for (int i = 0; i < 3; i++) bars[i].rotY(i * 60 + st * 15).key(tk, 4);
            for (int k = 0; k < 12; k++) {
                double a = Math.toRadians(k * 30 + st * 15);
                gems[k].pos(r * Math.cos(a), .25 + .1 * Math.sin(st + k), r * Math.sin(a)).rotY(st * 20).key(tk, 4);
            }
        }
        for (Stage.Part p : bars) p.hide().key(to, 6);
        for (Stage.Part p : gems) p.hide().key(to, 6);
    }

    // ------------------------------------------------------------------
    // 1) JACK_BOX: el payaso de la caja sorpresa
    // ------------------------------------------------------------------

    private static final class Clown {
        final Stage.Part spring, head, mouth;
        final Stage.Part[] rings = new Stage.Part[4];
        final List<Stage.Part> parts = new ArrayList<>();
        final List<double[]> offs = new ArrayList<>();

        Clown(Stage s) {
            spring = s.part(Material.LIGHT_GRAY_CONCRETE).size(.6, 1, .6);
            for (int i = 0; i < 4; i++) rings[i] = s.part(Material.IRON_BLOCK).size(1.4, .18, 1.4);
            head = s.part(Material.WHITE_CONCRETE).size(2.2);
            face(s, Material.RED_CONCRETE, 0, -.1, 1.2, .55, .55, .55);           // nariz
            face(s, Material.BLACK_CONCRETE, -.55, .4, 1.1, .4, .4, .25);         // ojo izq.
            face(s, Material.BLACK_CONCRETE, .55, .4, 1.1, .4, .4, .25);          // ojo der.
            mouth = face(s, Material.RED_CONCRETE, 0, -.65, 1.1, 1.4, .25, .2);   // boca
            face(s, Material.ORANGE_CONCRETE, -1.35, .15, 0, .7, .9, .7);         // pelo
            face(s, Material.ORANGE_CONCRETE, 1.35, .15, 0, .7, .9, .7);
            face(s, Material.PURPLE_CONCRETE, 0, 1.35, 0, 2, .5, 2);              // sombrero
            face(s, Material.YELLOW_CONCRETE, 0, 1.85, 0, 1.5, .5, 1.5);
            face(s, Material.PURPLE_CONCRETE, 0, 2.35, 0, 1, .5, 1);
            face(s, Material.YELLOW_CONCRETE, 0, 2.8, 0, .55, .55, .55);          // pompón
        }

        private Stage.Part face(Stage s, Material m, double x, double y, double z,
                                double sx, double sy, double sz) {
            Stage.Part p = s.part(m).size(sx, sy, sz);
            parts.add(p);
            offs.add(new double[]{x, y, z});
            return p;
        }

        /** Resorte de longitud len, inclinado phi grados hacia el jugador. */
        void pose(int tick, int dur, double len, double phiDeg, double mouthH) {
            double phi = Math.toRadians(phiDeg);
            double c = Math.cos(phi), sn = Math.sin(phi);
            spring.pos(0, 1.6 + c * len / 2, sn * len / 2).size(.6, len, .6).rotX(phiDeg).key(tick, dur);
            double[] f = {.2, .4, .6, .8};
            for (int i = 0; i < 4; i++) {
                rings[i].pos(0, 1.6 + c * len * f[i], sn * len * f[i]).rotX(phiDeg).key(tick, dur);
            }
            double hy = 1.6 + c * (len + 1), hz = sn * (len + 1);
            head.pos(0, hy, hz).rotX(phiDeg).key(tick, dur);
            mouth.size(1.4, mouthH, .2);
            for (int i = 0; i < parts.size(); i++) {
                double[] o = offs.get(i);
                parts.get(i).pos(o[0], hy + o[1] * c - o[2] * sn, hz + o[1] * sn + o[2] * c)
                        .rotX(phiDeg).key(tick, dur);
            }
        }

        void hide(int tick, int dur) {
            spring.hide().key(tick, dur);
            head.hide().key(tick, dur);
            for (Stage.Part p : rings) p.hide().key(tick, dur);
            for (Stage.Part p : parts) p.hide().key(tick, dur);
        }
    }

    private static void crank(Stage.Part arm, Stage.Part knob, int tick, int dur, double deg) {
        double a = Math.toRadians(deg);
        double dy = Math.cos(a), dz = Math.sin(a);
        arm.pos(1.5, .8 + .5 * dy, .5 * dz).size(.2, 1.0, .2).rotX(deg).key(tick, dur);
        knob.pos(1.6, .8 + dy, dz).size(.35).key(tick, dur);
    }

    private boolean jackBox(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 7.0));

        Stage.Part base = s.part(Material.RED_CONCRETE).pos(0, .8, 0).size(2.4, 1.6, 2.4);
        Stage.Part rim = s.part(Material.YELLOW_CONCRETE).pos(0, 1.7, 0).size(2.7, .3, 2.7);
        Stage.Part lid = s.part(Material.BLUE_CONCRETE).pos(0, 1.95, 0).size(2.5, .25, 2.5);
        Stage.Part hub = s.part(Material.IRON_BLOCK).pos(1.35, .8, 0).size(.3);
        Stage.Part arm = s.part(Material.IRON_BLOCK);
        Stage.Part knob = s.part(Material.RED_CONCRETE);
        Clown clown = new Clown(s);

        // 1) aparece la caja
        base.key(1, 6);
        rim.key(1, 6);
        lid.key(1, 6);
        hub.key(1, 6);
        crank(arm, knob, 1, 6, 0);
        s.sound(1, Sound.BLOCK_PISTON_EXTEND, 1.2f, 1.6f);
        s.particles(1, Particle.POOF, 0, 1, 0, 25, 1.2, .5, 1.2, .03);

        // 2) la manivela gira y suena la cajita de música; la caja tiembla
        float[] mel = {.84f, .94f, 1.06f, 1.12f, 1.26f, 1.12f, 1.06f, .94f, .84f, 1.06f, 1.26f, 1.5f};
        for (int i = 0; i < 12; i++) {
            int t0 = 6 + i * 4;
            boolean up = i % 2 == 0;
            crank(arm, knob, t0, 4, (i + 1) * 60);
            s.sound(t0, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.4f, mel[i]);
            s.sound(t0, Sound.BLOCK_GRINDSTONE_USE, .3f, 1.4f);
            lid.pos(0, up ? 2.07 : 1.95, 0).key(t0, 4);
            base.size(up ? 2.46 : 2.4, 1.6, up ? 2.46 : 2.4).key(t0, 4);
        }

        // 3) ¡BOING! La tapa sale volando y el payaso sale disparado
        s.sound(52, Sound.BLOCK_PISTON_EXTEND, 2f, .7f);
        s.sound(52, Sound.ENTITY_SLIME_JUMP, 2f, .6f);
        s.sound(52, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.5f, 1f);
        confetti(s, 52, 0, 3, 0, 40);
        s.particles(52, Particle.FIREWORK, 0, 3, 0, 40, .6, .6, .6, .15);
        lid.pos(1.8, 8, .8).size(2.5, .25, 2.5).rotZ(110).key(52, 7);
        lid.pos(3.2, 11.5, 1.5).rotZ(220).key(59, 7);
        lid.hide().key(66, 4);

        double[] hs = {6.6, 5.6, 6.5, 5.9, 6.3, 6.1};
        for (int i = 0; i < hs.length; i++) {
            clown.pose(52 + i * 6, 6, hs[i], 0, .25);
            s.sound(52 + i * 6, Sound.BLOCK_PISTON_EXTEND, .8f, 1.2f + i * .1f);
        }

        // 4) se ríe del jugador
        s.sound(58, Sound.ENTITY_WITCH_CELEBRATE, 2.5f, .9f);
        s.sound(86, Sound.ENTITY_WITCH_CELEBRATE, 2.5f, .75f);
        for (int i = 0; i < 6; i++) {
            int tk = 84 + i * 4;
            clown.pose(tk, 3, 6.1, i % 2 == 0 ? 6 : -6, i % 2 == 0 ? .7 : .25);
            s.sound(tk, Sound.ENTITY_WITCH_AMBIENT, 1.2f, .8f + (i % 2) * .2f);
        }

        // 5) ¡BUU! Se abalanza sobre el jugador (la cara mira hacia abajo, al jugador)
        clown.pose(106, 5, 6.5, 60, .95);
        s.sound(106, Sound.ENTITY_GHAST_SCREAM, 2.5f, .7f);
        s.at(111, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(111, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.6f);
        confetti(s, 111, 0, 1.5, 7, 70);
        s.particles(111, Particle.FIREWORK, 0, 1.5, 7, 60, 1, 1, 1, .25);

        // 6) se esconde de nuevo
        clown.pose(120, 8, .6, 0, .25);
        clown.hide(130, 5);
        base.hide().key(132, 6);
        rim.hide().key(132, 6);
        hub.hide().key(132, 6);
        arm.hide().key(132, 6);
        knob.hide().key(132, 6);

        launch(s, 142, h);
        return true;
    }

    // ------------------------------------------------------------------
    // 2) JAWS: mandíbulas gigantes
    // ------------------------------------------------------------------

    private static final class Maw {
        final int n;
        final Stage.Part plate, gum;
        final Stage.Part[] up, low;

        Maw(Stage s, int n) {
            this.n = n;
            plate = s.part(Material.DARK_OAK_PLANKS).size(11, 1.2, 11);
            gum = s.part(Material.RED_CONCRETE).size(9.8, .4, 9.8);
            up = new Stage.Part[n];
            low = new Stage.Part[n];
            for (int i = 0; i < n; i++) {
                up[i] = s.part(Material.WHITE_CONCRETE).size(1.2, 3.0, 1.2)
                        .rotY(-Math.toDegrees((i + .5) * 2 * Math.PI / n));
                low[i] = s.part(Material.WHITE_CONCRETE).size(1.2, 2.2, 1.2)
                        .rotY(-Math.toDegrees(i * 2 * Math.PI / n));
            }
        }

        void upper(int tick, int dur, double y, double r) {
            plate.pos(0, y, 0).key(tick, dur);
            gum.pos(0, y - .8, 0).key(tick, dur);
            for (int i = 0; i < n; i++) {
                double a = (i + .5) * 2 * Math.PI / n;
                up[i].pos(r * Math.cos(a), y - 2.5, r * Math.sin(a)).key(tick, dur);
            }
        }

        void lower(int tick, int dur, double y, double r, int stagger) {
            for (int i = 0; i < n; i++) {
                double a = i * 2 * Math.PI / n;
                low[i].pos(r * Math.cos(a), y, r * Math.sin(a)).key(tick + i * stagger, dur);
            }
        }

        void hideAll(int tick) {
            plate.hide().key(tick, 0);
            gum.hide().key(tick, 0);
            for (int i = 0; i < n; i++) {
                up[i].hide().key(tick, 0);
                low[i].hide().key(tick, 0);
            }
        }
    }

    private boolean jaws(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 0));
        final double r0 = 4.6, r1 = 2.5;
        Maw m = new Maw(s, 12);
        BlockData ground = groundData(s.origin);

        // 1) los dientes de abajo brotan del suelo en cadena
        s.sound(1, Sound.BLOCK_DEEPSLATE_BREAK, 2f, .5f);
        s.sound(8, Sound.ENTITY_RAVAGER_ROAR, 2f, .6f);
        m.lower(1, 0, -2.5, r0, 0);
        m.lower(2, 10, .9, r0, 1);
        for (int i = 0; i < 12; i++) {
            double a = i * 2 * Math.PI / 12;
            s.particles(2 + i, Particle.BLOCK, r0 * Math.cos(a), .2, r0 * Math.sin(a), 14, .4, .2, .4, .05, ground);
        }

        // 2) baja la mandíbula superior, lenta y amenazante
        m.upper(10, 0, 17, r0);
        m.upper(14, 16, 7.0, r0);
        s.sound(14, Sound.ENTITY_PHANTOM_SWOOP, 2f, .5f);

        // 3) ¡CHOMP!
        m.upper(30, 5, 4.6, r1);
        m.lower(30, 5, .9, r1, 0);
        s.at(35, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(35, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 3f, .7f);
        s.sound(35, Sound.BLOCK_ANVIL_LAND, 3f, .5f);
        s.sound(35, Sound.BLOCK_STONE_BREAK, 3f, .6f);
        s.ringXZ(35, Particle.CLOUD, 0, .3, 0, 3.5, 36);
        s.ringXZ(37, Particle.CLOUD, 0, .3, 0, 5, 40);
        s.particles(35, Particle.CRIT, 0, 1.2, 0, 50, 1.5, .8, 1.5, .3);
        s.particles(35, Particle.BLOCK, 0, .5, 0, 60, 2, .4, 2, .1, ground);
        for (int k = 0; k < 6; k++) m.upper(36 + k * 2, 2, 4.6 + (k % 2 == 0 ? .15 : 0), r1);

        // 4) abre las fauces y se esconde
        m.upper(52, 14, 17, r0);
        m.lower(54, 10, -2.5, r0, 0);
        m.hideAll(68);

        launch(s, 70, h);
        return true;
    }

    // ------------------------------------------------------------------
    // 3) CLOCK: reloj gigante, cuenta atrás y campanadas
    // ------------------------------------------------------------------

    private static void hand(Stage.Part p, double cy, double len, double deg, double z, double w, int tick, int dur) {
        double a = Math.toRadians(deg);
        p.pos(len / 2 * Math.sin(a), cy + len / 2 * Math.cos(a), z).size(w, len, .14).rotZ(-deg).key(tick, dur);
    }

    private boolean clock(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 7.5));
        final double cy = 8;
        List<Stage.Part> all = new ArrayList<>();

        Stage.Part plate = s.part(Material.GOLD_BLOCK).billboard(Display.Billboard.VERTICAL)
                .pos(0, cy, -.4).size(10.4, 10.4, .5);
        Stage.Part face = s.part(Material.CLOCK).billboard(Display.Billboard.VERTICAL)
                .pos(0, cy, 0).size(10, 10, 2);
        Stage.Part cap = s.part(Material.IRON_BLOCK).billboard(Display.Billboard.VERTICAL)
                .pos(0, cy, .5).size(.8, .8, .3);
        Stage.Part hour = s.part(Material.BLACK_CONCRETE).billboard(Display.Billboard.VERTICAL);
        Stage.Part minute = s.part(Material.BLACK_CONCRETE).billboard(Display.Billboard.VERTICAL);
        Stage.Part second = s.part(Material.RED_CONCRETE).billboard(Display.Billboard.VERTICAL);
        all.add(plate);
        all.add(face);
        all.add(cap);
        all.add(hour);
        all.add(minute);
        all.add(second);
        for (int i = 0; i < 12; i++) {
            double th = i * 30, a = Math.toRadians(th);
            boolean q = i % 3 == 0;
            all.add(s.part(Material.BLACK_CONCRETE).billboard(Display.Billboard.VERTICAL)
                    .pos(4.3 * Math.sin(a), cy + 4.3 * Math.cos(a), .2)
                    .size(q ? .5 : .3, q ? 1.3 : .9, .14).rotZ(-th));
        }

        // 1) aparece
        for (Stage.Part p : all) p.key(1, 8);
        hand(hour, cy, 3.2, -30, .15, .5, 1, 8);
        hand(minute, cy, 4.2, -120, .3, .35, 1, 8);
        hand(second, cy, 4.8, 0, .45, .2, 1, 8);
        s.sound(1, Sound.BLOCK_BEACON_ACTIVATE, 2f, 1.4f);

        // 2) cuenta atrás: el segundero se acelera
        int[] iv = {5, 5, 5, 5, 4, 4, 4, 4, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2};
        int tk = 10;
        for (int i = 0; i < iv.length; i++) {
            hand(second, cy, 4.8, (i + 1) * 30, .45, .2, tk, iv[i]);
            hand(minute, cy, 4.2, -120 + (i + 1) * 5, .3, .35, tk, iv[i]);
            hand(hour, cy, 3.2, -30 + (i + 1) * 30 / 24.0, .15, .5, tk, iv[i]);
            s.sound(tk, Sound.BLOCK_NOTE_BLOCK_HAT, 1.6f, 1.2f + i * .035f);
            tk += iv[i];
        }

        // 3) las doce: tres campanadas
        for (int k = 0; k < 3; k++) {
            int tt = 84 + k * 8;
            s.sound(tt, Sound.BLOCK_BELL_USE, 4f, .5f);
            s.sound(tt, Sound.BLOCK_NOTE_BLOCK_BASS, 3f, .5f);
            s.particles(tt, Particle.NOTE, 0, cy, 1, 12, 3, 3, .5, 1);
            plate.size(10.9, 10.9, .5).key(tt, 2);
            plate.size(10.4, 10.4, .5).key(tt + 2, 4);
        }

        // 4) estalla en mil pedazos
        s.at(104, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(104, Sound.BLOCK_GLASS_BREAK, 3f, .6f);
        s.sound(104, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1.3f);
        s.particles(104, Particle.EXPLOSION, 0, cy, 1, 8, 3, 3, .5, 0);
        s.particles(104, Particle.CRIT, 0, 1.2, 7.5, 40, .6, .8, .6, .3);
        for (Stage.Part p : all) {
            p.move(rnd().nextDouble(-7, 7), rnd().nextDouble(-4, 3), rnd().nextDouble(2, 6))
                    .rotYXZ(rnd().nextDouble(-160, 160), rnd().nextDouble(-160, 160), rnd().nextDouble(-160, 160))
                    .key(104, 12);
        }
        for (Stage.Part p : all) p.hide().key(118, 2);

        launch(s, 124, h);
        return true;
    }

    // ------------------------------------------------------------------
    // 4) BELL: campana gigante
    // ------------------------------------------------------------------

    private boolean bell(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 6.5));
        final double piv = 13.55, len = 4.0;

        Stage.Part beam = s.part(Material.DARK_OAK_LOG).pos(0, piv, 0).size(13.4, .9, .9);
        Stage.Part postL = s.part(Material.DARK_OAK_LOG).pos(-6.3, 7, 0).size(.9, 14, .9);
        Stage.Part postR = s.part(Material.DARK_OAK_LOG).pos(6.3, 7, 0).size(.9, 14, .9);
        Stage.Part bell = s.part(Material.BELL).billboard(Display.Billboard.VERTICAL).size(8);

        beam.key(1, 8);
        postL.key(1, 8);
        postR.key(1, 8);
        swing(bell, piv, len, 0, 1, 8);
        s.sound(1, Sound.BLOCK_PISTON_EXTEND, 1.5f, .6f);

        double[] ext = {32, -34, 36, -38};
        int[] start = {6, 16, 32, 48};
        int[] dur = {10, 16, 16, 16};
        for (int i = 0; i < 4; i++) {
            swing(bell, piv, len, ext[i], start[i], dur[i]);
            int arrive = start[i] + dur[i];
            double a = Math.toRadians(ext[i]);
            double bx = len * Math.sin(a), by = piv - len * Math.cos(a);
            s.sound(arrive, Sound.BLOCK_BELL_USE, 4f, .55f);
            s.sound(arrive, Sound.BLOCK_BELL_RESONATE, 3f, .55f);
            s.particles(arrive, Particle.NOTE, bx, by, 1, 10, 2, 2, .3, 1);
            for (int r = 0; r < 4; r++) s.ringXY(arrive + r, Particle.CLOUD, bx, by, .5, 2 + r * 1.5, 24);
        }

        // el campanazo final: la campana se lanza contra el jugador
        bell.pos(0, 4.8, 5.2).size(12).rotZ(0).key(66, 6);
        s.sound(66, Sound.ENTITY_PHANTOM_SWOOP, 2f, .5f);
        s.at(72, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(72, Sound.BLOCK_BELL_USE, 5f, .5f);
        s.sound(72, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1.2f);
        s.particles(72, Particle.NOTE, 0, 4, 6, 40, 3, 3, 1, 1);
        for (int r = 0; r < 5; r++) s.ringXY(72 + r, Particle.CLOUD, 0, 4.8, 5.5, 2 + r * 1.8, 30);

        bell.pos(0, 16, 5.2).size(12).key(80, 8);
        bell.hide().key(88, 0);
        beam.hide().key(84, 8);
        postL.hide().key(84, 8);
        postR.hide().key(84, 8);

        launch(s, 96, h);
        return true;
    }

    private static void swing(Stage.Part bell, double piv, double len, double deg, int tick, int dur) {
        double a = Math.toRadians(deg);
        bell.pos(len * Math.sin(a), piv - len * Math.cos(a), 0).size(8).rotZ(deg).key(tick, dur);
    }

    // ------------------------------------------------------------------
    // 5) BLIZZARD: copo gigante, estacas de hielo y bloque que se rompe
    // ------------------------------------------------------------------

    private boolean blizzard(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 0));
        final UUID id = t.getUniqueId();

        // copo: 3 barras largas + 3 cortas + núcleo
        double[] base = {0, 60, 120, 30, 90, 150, 0};
        double[] yy = {.25, .25, .25, .5, .5, .5, .6};
        double[][] sz = {{8.4, .45, .45}, {8.4, .45, .45}, {8.4, .45, .45},
                {5.2, .35, .35}, {5.2, .35, .35}, {5.2, .35, .35}, {1.7, 1.7, 1.7}};
        Material[] mat = {Material.PACKED_ICE, Material.PACKED_ICE, Material.PACKED_ICE,
                Material.BLUE_ICE, Material.BLUE_ICE, Material.BLUE_ICE, Material.SEA_LANTERN};
        Stage.Part[] flake = new Stage.Part[7];
        for (int i = 0; i < 7; i++) flake[i] = s.part(mat[i]);

        for (int i = 0; i < 7; i++) flake[i].pos(0, yy[i], 0).size(sz[i][0], sz[i][1], sz[i][2]).rotY(base[i]).key(1, 8);
        s.sound(1, Sound.ENTITY_PLAYER_HURT_FREEZE, 2f, .6f);
        s.sound(1, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2f, .5f);
        for (int st = 1; st <= 17; st++) {
            int tk = 6 + 4 * st;
            double rise = Math.max(0, Math.min(1, (tk - 30) / 24.0)) * 3.8;
            for (int i = 0; i < 7; i++) {
                flake[i].pos(0, yy[i] + rise, 0).rotY(base[i] + st * 45).key(tk, 4);
            }
        }

        // estacas de hielo que brotan alrededor, inclinadas hacia el jugador
        Stage.Part[] spike = new Stage.Part[8];
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            Quaternionf q = new Quaternionf().rotateY((float) -a).rotateZ((float) Math.toRadians(18));
            spike[i] = s.part(Material.PACKED_ICE).size(.7, 3.4, .7).rot(q);
            spike[i].pos(2.4 * Math.cos(a), -2, 2.4 * Math.sin(a)).key(1, 0);
            spike[i].pos(2.4 * Math.cos(a), 1.55, 2.4 * Math.sin(a)).key(8 + i, 8);
            s.sound(8 + i, Sound.BLOCK_GLASS_PLACE, 1.5f, .6f + i * .08f);
            s.particles(8 + i, Particle.SNOWFLAKE, 2.4 * Math.cos(a), .3, 2.4 * Math.sin(a), 15, .3, .3, .3, .05);
        }

        // el hielo cubre al jugador
        Stage.Part shell = s.part(Material.ICE).size(1.6, 2.5, 1.6).pos(0, 1.25, 0);
        shell.key(40, 6);
        s.sound(40, Sound.ENTITY_PLAYER_HURT_FREEZE, 2f, .5f);

        // ventisca de partículas + pantalla congelada
        s.every(4, 74, tk -> s.world.spawnParticle(Particle.SNOWFLAKE, s.loc(0, 2, 0), 8, 3, 1.5, 3, .02));
        s.every(20, 74, tk -> {
            if (tk % 3 != 0) return;
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) p.setFreezeTicks(Math.max(0, p.getMaxFreezeTicks() - 1));
        });
        s.onClose(() -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) p.setFreezeTicks(0);
        });

        // se rompe todo
        s.at(76, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(76, Sound.BLOCK_GLASS_BREAK, 3f, .8f);
        s.sound(76, Sound.BLOCK_GLASS_BREAK, 3f, 1.2f);
        s.sound(76, Sound.ENTITY_PLAYER_HURT_FREEZE, 3f, 1.4f);
        s.particles(76, Particle.ITEM, 0, 1.2, 0, 90, .8, 1, .8, .2, new ItemStack(Material.ICE));
        s.particles(76, Particle.SNOWFLAKE, 0, 1.2, 0, 80, 1.5, 1.2, 1.5, .15);
        s.particles(76, Particle.BLOCK, 0, 1, 0, 60, 1, 1, 1, .1, Material.ICE.createBlockData());
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            spike[i].move(3 * Math.cos(a), .6, 3 * Math.sin(a))
                    .rotYXZ(rnd().nextDouble(-160, 160), rnd().nextDouble(-160, 160), rnd().nextDouble(-160, 160))
                    .key(76, 10);
            spike[i].hide().key(87, 0);
        }
        shell.size(3.4, 4.2, 3.4).key(76, 3);
        shell.hide().key(79, 0);
        for (int i = 0; i < 7; i++) flake[i].hide().key(76, 6);

        launch(s, 92, h);
        return true;
    }

    // ------------------------------------------------------------------
    // 6) ANVIL: yunque de dibujos animados
    // ------------------------------------------------------------------

    private boolean anvil(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 0));
        BlockData ground = groundData(s.origin);

        Stage.Part sh1 = s.part(Material.BLACK_STAINED_GLASS).pos(0, .03, 0).size(.4, .02, .4);
        Stage.Part sh2 = s.part(Material.BLACK_STAINED_GLASS).pos(0, .03, 0).size(.4, .02, .4).rotY(45);
        Stage.Part an = s.part(Material.ANVIL).pos(0, 28, 0).size(3.6);

        sh1.key(1, 0);
        sh2.key(1, 0);
        sh1.size(5.2, .02, 5.2).key(2, 23);
        sh2.size(5.2, .02, 5.2).key(2, 23);

        an.key(2, 0);
        an.pos(0, 14, 0).rotY(20).key(3, 14);
        an.pos(0, 1.8, 0).rotY(0).key(17, 8);
        s.sound(2, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2f, 1.6f);
        s.sound(14, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2f, 2f);

        // ¡PLAF! y rebote de dibujos animados
        s.at(25, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(25, Sound.BLOCK_ANVIL_LAND, 4f, .5f);
        s.sound(25, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.8f);
        s.sound(25, Sound.ENTITY_PLAYER_BIG_FALL, 2f, .6f);
        for (int r = 0; r < 5; r++) s.ringXZ(25 + r, Particle.CLOUD, 0, .3, 0, 1.5 + r * 1.2, 28);
        s.particles(25, Particle.BLOCK, 0, .5, 0, 80, 2, .4, 2, .12, ground);
        s.particles(25, Particle.CRIT, 0, 3.5, 0, 30, 1.2, .3, 1.2, .2);
        an.pos(0, 1.1, 0).size(4.8, 2.2, 4.8).key(25, 2);
        an.pos(0, 2.05, 0).size(3.2, 4.1, 3.2).key(27, 3);
        an.pos(0, 1.8, 0).size(3.6).key(30, 4);

        // se hunde en el suelo
        sh1.hide().key(44, 3);
        sh2.hide().key(44, 3);
        an.pos(0, -2.2, 0).key(46, 12);
        an.hide().key(58, 0);

        launch(s, 62, h);
        return true;
    }

    // ------------------------------------------------------------------
    // 7) BLACKHOLE: agujero negro
    // ------------------------------------------------------------------

    private boolean blackhole(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 5.5));
        final double cy = 3;
        final int n = 16;

        Stage.Part core = s.part(Material.BLACK_CONCRETE).pos(0, cy, 0);
        Stage.Part halo = s.part(Material.PURPLE_STAINED_GLASS).pos(0, cy, 0);
        Material[] mats = {Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.END_STONE,
                Material.PURPLE_CONCRETE, Material.MAGENTA_CONCRETE, Material.AMETHYST_BLOCK};
        Stage.Part[] deb = new Stage.Part[n];
        double[] a0 = new double[n], r0 = new double[n], tilt = new double[n];
        for (int i = 0; i < n; i++) {
            deb[i] = s.part(mats[i % mats.length]);
            a0[i] = i * 360.0 / n + rnd().nextDouble(-10, 10);
            r0[i] = 5 + (i % 4) * .8;
            tilt[i] = Math.toRadians(20 + (i % 3) * 25);
        }

        s.sound(2, Sound.BLOCK_END_PORTAL_SPAWN, 2f, .6f);
        core.size(1.8).key(2, 12);
        halo.size(3.0).key(2, 12);

        for (int st = 0; st <= 30; st++) {
            int tk = 4 + 2 * st;
            double u = st / 30.0;
            for (int i = 0; i < n; i++) {
                double r = Math.max(.7, r0[i] * Math.pow(1 - u, 1.3));
                double ang = a0[i] + (u + 2.2 * u * u) * 540;
                double ar = Math.toRadians(ang);
                deb[i].pos(r * Math.cos(ar), cy + r * Math.sin(ar) * Math.sin(tilt[i]), r * Math.sin(ar) * Math.cos(tilt[i]))
                        .size(.6 * (1 - .6 * u)).rotYXZ(ang, ang * 1.3, 0).key(tk, 2);
            }
            if (st >= 4) {
                core.size(1.8 + u * .8).rotYXZ(st * 20, st * 35, 0).key(tk, 2);
                halo.size(3.0 + u * .6).rotYXZ(-st * 15, -st * 25, 0).key(tk, 2);
            }
            if (st % 5 == 0) s.sound(tk, Sound.BLOCK_PORTAL_TRAVEL, .5f, 1.5f);
        }

        // rayo de succión hacia el jugador
        s.every(36, 62, tk -> {
            for (int k = 0; k <= 5; k++) {
                double f = k / 5.0;
                s.world.spawnParticle(Particle.PORTAL, s.loc(0, cy - 1.8 * f, 5.5 * f), 2, .2, .2, .2, .05);
            }
        });

        s.at(62, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(62, Sound.ENTITY_ENDERMAN_TELEPORT, 3f, .5f);
        s.sound(62, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, .8f);
        s.particles(62, Particle.PORTAL, 0, 1.2, 5.5, 120, .5, 1, .5, 1.2);
        core.size(4.6).key(62, 3);
        core.hide().key(65, 4);
        halo.hide().key(65, 4);
        for (Stage.Part p : deb) p.hide().key(66, 2);
        s.particles(65, Particle.REVERSE_PORTAL, 0, cy, 0, 80, 1.5, 1.5, 1.5, .1);

        launch(s, 74, h);
        return true;
    }

    // ------------------------------------------------------------------
    // 8) VEX_SWARM: enjambre de Vex
    // ------------------------------------------------------------------

    private boolean vexSwarm(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 0));
        final int n = 10;
        final Vex[] vex = new Vex[n];

        ritual(s, Material.CRYING_OBSIDIAN, Material.AMETHYST_BLOCK, 4.2, 1, 64);

        s.at(0, () -> {
            for (int i = 0; i < n; i++) {
                double[] p = swarmPos(i, n, 0);
                Location l = s.loc(p[0], p[1], p[2]);
                vex[i] = mob(s, l, Vex.class, v -> {
                    v.setAI(false);
                    v.setGravity(false);
                    v.setInvulnerable(true);
                    v.setPersistent(false);
                    v.setRemoveWhenFarAway(true);
                    v.setSilent(true);
                    v.setCollidable(false);
                    v.setCharging(true);
                });
            }
            s.world.spawnParticle(Particle.SOUL, s.loc(0, 1, 0), 60, 3, .5, 3, .03);
        });
        s.sound(0, Sound.ENTITY_VEX_CHARGE, 2f, .7f);
        s.sound(0, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.5f, 1.2f);
        s.sound(30, Sound.ENTITY_VEX_AMBIENT, 2f, .8f);
        s.sound(52, Sound.ENTITY_VEX_CHARGE, 2.5f, 1.4f);

        s.every(1, 62, tk -> {
            for (int i = 0; i < n; i++) {
                Vex v = vex[i];
                if (v == null || !v.isValid()) continue;
                double[] p = swarmPos(i, n, tk);
                Location next = s.loc(p[0], p[1], p[2]);
                Vector d = next.toVector().subtract(v.getLocation().toVector());
                if (d.lengthSquared() > 0.0001) {
                    next.setYaw((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())));
                }
                v.teleport(next);
            }
            if (tk % 4 == 0) s.world.spawnParticle(Particle.WITCH, s.loc(0, 2.5, 0), 10, 2.5, 1.5, 2.5, .02);
        });

        s.at(63, () -> {
            h.vanish().run();
            h.impact().run();
            for (Vex v : vex) {
                if (v != null && v.isValid()) {
                    s.world.spawnParticle(Particle.POOF, v.getLocation().add(0, .5, 0), 6, .2, .2, .2, .02);
                    v.remove();
                }
            }
        });
        s.sound(63, Sound.ENTITY_VEX_DEATH, 3f, .6f);
        s.sound(63, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.4f);
        s.particles(63, Particle.TOTEM_OF_UNDYING, 0, 1.2, 0, 70, .5, 1, .5, .6);
        s.particles(63, Particle.SOUL, 0, 1.2, 0, 50, .5, 1, .5, .1);

        launch(s, 70, h);
        return true;
    }

    /** Posición local del Vex i en el tick tk: espiral ascendente, cierre y picado. */
    private static double[] swarmPos(int i, int n, int tk) {
        double rad, h;
        if (tk <= 28) {
            double f = tk / 28.0;
            rad = 4.5 - 1.0 * f;
            h = .4 + 3.2 * f;
        } else if (tk <= 52) {
            double f = (tk - 28) / 24.0;
            rad = 3.5 - 1.2 * f;
            h = 3.6 - .4 * f;
        } else {
            double f = Math.min(1, (tk - 52) / 10.0);
            rad = 2.3 * (1 - f) + .15;
            h = 3.2 * (1 - f) + 1.0;
        }
        double theta = Math.toRadians(i * 360.0 / n + 12 * tk + 15.0 * tk * tk / 66.0);
        double bob = .3 * Math.sin(tk * .4 + i);
        return new double[]{rad * Math.cos(theta), h + bob, rad * Math.sin(theta)};
    }

    // ------------------------------------------------------------------
    // 9) EVOKER_FANGS: jaula de colmillos
    // ------------------------------------------------------------------

    private boolean evokerFangs(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 0));
        final Evoker[] evoker = new Evoker[1];
        final Location playerLoc = t.getLocation();

        ritual(s, Material.GOLD_BLOCK, Material.YELLOW_CONCRETE, 3.4, 1, 60);

        // el Evoker aparece delante del jugador (-Z local = hacia donde mira el jugador)
        s.at(0, () -> {
            Location ev = s.loc(0, 0, -6.5);
            ev.setDirection(playerLoc.toVector().subtract(ev.toVector()).setY(0));
            evoker[0] = mob(s, ev, Evoker.class, e -> {
                e.setAI(false);
                e.setInvulnerable(true);
                e.setPersistent(false);
                e.setRemoveWhenFarAway(true);
                e.setSilent(true);
                e.setCollidable(false);
                e.setSpell(Spellcaster.Spell.FANGS);
            });
            s.world.spawnParticle(Particle.WITCH, ev.clone().add(0, 1, 0), 40, .5, 1, .5, .05);
        });
        s.sound(0, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 2.5f, .9f);

        // línea de colmillos hacia el jugador
        for (int k = 0; k < 3; k++) {
            final double z = -4.8 + 1.2 * k;
            s.at(12 + k * 2, () -> fang(s, evoker, s.loc(0, 0, z)));
            s.sound(12 + k * 2, Sound.ENTITY_EVOKER_FANGS_ATTACK, 1.5f, 1f);
        }
        // tres anillos que se van cerrando
        fangRing(s, evoker, 2.6, 10, 26);
        fangRing(s, evoker, 1.9, 8, 40);
        fangRing(s, evoker, 1.4, 6, 52);

        s.at(58, () -> {
            h.vanish().run();
            h.impact().run();
        });
        s.sound(58, Sound.ENTITY_EVOKER_CELEBRATE, 3f, .8f);
        s.sound(58, Sound.ENTITY_EVOKER_CAST_SPELL, 2f, .6f);
        s.particles(58, Particle.TOTEM_OF_UNDYING, 0, 1.2, 0, 60, .6, 1, .6, .5);
        s.at(70, () -> {
            Evoker e = evoker[0];
            if (e != null && e.isValid()) {
                s.world.spawnParticle(Particle.POOF, e.getLocation().add(0, 1, 0), 25, .4, .8, .4, .03);
                e.remove();
            }
        });

        launch(s, 74, h);
        return true;
    }

    private void fangRing(Stage s, Evoker[] owner, double radius, int count, int startTick) {
        for (int i = 0; i < count; i++) {
            final double a = i * 2 * Math.PI / count;
            s.at(startTick + i, () -> fang(s, owner, s.loc(radius * Math.cos(a), 0, radius * Math.sin(a))));
        }
        s.sound(startTick, Sound.ENTITY_EVOKER_FANGS_ATTACK, 2f, .8f);
        s.sound(startTick + count / 2, Sound.ENTITY_EVOKER_FANGS_ATTACK, 2f, 1.1f);
    }

    private void fang(Stage s, Evoker[] owner, Location loc) {
        mob(s, loc, EvokerFangs.class, f -> {
            f.addScoreboardTag(FANG_TAG);
            f.setOwner(owner[0]);
        });
    }

    // ------------------------------------------------------------------
    // 10) GIANT_PUNT: el Gigante te manda de patitas al cielo
    // ------------------------------------------------------------------

    private boolean giantPunt(Player t, Hooks h) {
        Stage s = stage(Stage.frontOf(t, 0));
        final UUID id = t.getUniqueId();
        final Giant[] giant = new Giant[1];
        final Location playerLoc = t.getLocation();
        // hacia donde mira el jugador (el Gigante aparece a su espalda y lo empuja hacia delante)
        final Vector fwd = s.loc(0, 0, -1).toVector().subtract(s.loc(0, 0, 0).toVector()).setY(0).normalize();
        BlockData ground = groundData(s.origin);

        s.at(0, () -> {
            Location l = s.loc(0, 0, 9);
            l.setDirection(playerLoc.toVector().subtract(l.toVector()).setY(0));
            giant[0] = mob(s, l, Giant.class, g -> {
                g.setAI(false);
                g.setInvulnerable(true);
                g.setPersistent(false);
                g.setRemoveWhenFarAway(true);
                g.setSilent(true);
                g.setCollidable(false);
                g.setAggressive(true);
            });
            s.world.spawnParticle(Particle.BLOCK, l, 60, 2, .2, 2, .1, ground);
        });
        s.sound(0, Sound.ENTITY_RAVAGER_ROAR, 3f, .4f);

        // pasos que hacen temblar el suelo
        double[] zs = {9, 7, 5.5, 4.2};
        int[] ts = {0, 10, 18, 26};
        for (int i = 1; i < zs.length; i++) {
            final double z = zs[i];
            s.at(ts[i], () -> {
                Giant g = giant[0];
                if (g == null || !g.isValid()) return;
                Location l = s.loc(0, 0, z);
                l.setDirection(playerLoc.toVector().subtract(l.toVector()).setY(0));
                g.teleport(l);
            });
            s.sound(ts[i], Sound.ENTITY_IRON_GOLEM_STEP, 4f, .4f);
            s.sound(ts[i], Sound.ENTITY_GENERIC_EXPLODE, 1f, .5f);
            s.ringXZ(ts[i], Particle.CLOUD, 0, .3, z, 2.5, 20);
            s.particles(ts[i], Particle.BLOCK, 0, .3, z, 30, 1.5, .2, 1.5, .1, ground);
        }

        // grietas en el suelo
        Stage.Part[] crack = new Stage.Part[4];
        for (int i = 0; i < 4; i++) {
            crack[i] = s.part(Material.BLACK_CONCRETE).pos(0, .03, 0).size(.3, .02, 9)
                    .rotY(i * 45 + rnd().nextDouble(-10, 10));
            crack[i].key(32, 3);
            crack[i].hide().key(56, 4);
        }

        // el golpe
        s.at(30, () -> {
            Giant g = giant[0];
            if (g != null && g.isValid()) g.swingMainHand();
        });
        s.sound(30, Sound.ENTITY_PHANTOM_SWOOP, 3f, .5f);
        s.at(32, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) return;
            p.setVelocity(fwd.clone().multiply(.5).setY(1.3));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 200, 0, false, false));
        });
        s.sound(32, Sound.ENTITY_WARDEN_ATTACK_IMPACT, 3f, .6f);
        s.sound(32, Sound.ENTITY_PLAYER_ATTACK_CRIT, 2f, .6f);
        s.particles(32, Particle.EXPLOSION, 0, 1, 0, 3, .5, .5, .5, 0);
        s.ringXZ(32, Particle.CLOUD, 0, .3, 0, 2, 20);
        s.ringXZ(34, Particle.CLOUD, 0, .3, 0, 4, 30);

        // estela mientras vuela
        s.every(33, 47, tk -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) return;
            Location l = p.getLocation().add(0, 1, 0);
            s.world.spawnParticle(Particle.CLOUD, l, 6, .2, .3, .2, .02);
            s.world.spawnParticle(Particle.FIREWORK, l, 2, .2, .2, .2, .05);
        });

        // en lo más alto, se ejecuta el comando
        s.at(48, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                Location l = p.getLocation().add(0, 1, 0);
                s.world.spawnParticle(Particle.FIREWORK, l, 60, .5, .5, .5, .25);
                s.world.playSound(l, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 3f, 1f);
                s.world.playSound(l, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 3f, 1f);
            }
            h.impact().run();
        });
        s.at(54, () -> {
            Giant g = giant[0];
            if (g != null && g.isValid()) {
                s.world.spawnParticle(Particle.POOF, g.getLocation().add(0, 3, 0), 60, 1.5, 3, 1.5, .03);
                g.remove();
            }
        });

        launch(s, 62, h);
        return true;
    }
}
