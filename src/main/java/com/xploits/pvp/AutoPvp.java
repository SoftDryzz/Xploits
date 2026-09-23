package com.xploits.pvp;

import com.xploits.XploitsAddon;
import com.xploits.autotpy.AutoTpy;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.core.ActionWatch;
import com.xploits.pvp.core.AllyPolicy;
import com.xploits.pvp.core.CombatDirector;
import com.xploits.pvp.core.CombatPosture;
import com.xploits.pvp.core.CombatSnapshot;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.DefensivePolicy;
import com.xploits.pvp.core.FriendLedger;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.core.ModuleLedger;
import com.xploits.pvp.core.Plan;
import com.xploits.pvp.core.Resource;
import com.xploits.pvp.core.Skipped;
import com.xploits.shared.XploitsModule;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dirige los módulos de combate de Meteor (spec §1, rediseñado en
 * {@code 2026-09-22-autopvp-decide-bien}). No ejecuta ninguna acción de combate: solo enciende y
 * apaga, y solo apaga lo que encendió él (spec §7).
 *
 * <p>Es el <b>adaptador</b>: mide el mundo y ejecuta lo que decide el núcleo, que es puro y va con
 * tests. Aquí no se decide nada. Lo que mide son la fase del enemigo, la mitad tuya del snapshot -la
 * que sostiene el eje defensivo del rediseño §5- y los ajustes ajenos de Meteor de los que depende
 * una decisión del núcleo, hoy solo el {@code anti-suicide} de {@code crystal-aura}.
 */
public class AutoPvp extends XploitsModule {
    private static final int FIRST_SLOT = 0;
    /** Ticks por segundo del juego: la única conversión que hace falta para informar de tiempos. */
    private static final int TICKS_PER_SECOND = 20;
    /**
     * Último slot de la hotbar (spec §6): lo que ven {@code InvUtils.findInHotbar}/{@code
     * testInHotbar}, y también el único rango que cuenta para {@code PICKAXE} — {@code AutoCity}
     * busca el pico con {@code InvUtils.find} sobre todo el inventario, pero rechaza el resultado
     * si {@code !isHotbar()} y se apaga solo con un error (spec §6). Contar la mochila para el pico
     * era sobreestimar exactamente el mismo fallo silencioso que este rango corrige para los otros
     * cinco.
     */
    private static final int HOTBAR_LAST_SLOT = 8;
    /** Último slot del inventario completo: hasta dónde llega el barrido único de {@link #inventory()}. */
    private static final int INVENTORY_LAST_SLOT = 35;

    /** Tope de nombres recordados para no repetir el aviso de "no ataco a"; al llenarse se vacía. */
    private static final int MAX_ANNOUNCED_ALLIES = 64;

    /**
     * Resistencia a explosiones a partir de la cual un bloque protege de un cristal (rediseño §4.1).
     * Es el mismo umbral que usa {@code PlayerUtils.isInHole(boolean)} de Meteor para decidir si un
     * vecino te protege, así que "enterrado" y "en un agujero" se miden con la misma vara.
     */
    private static final float PROTECTIVE_BLAST_RESISTANCE = 600f;

    /** Módulos de combate que auto-pvp nunca toca, los lleves encendidos o no (spec §7). */
    private static final List<String> ALWAYS_YOURS = List.of("auto-totem", "auto-armor", "offhand", "auto-weapon");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> targetRange = sgGeneral.add(new IntSetting.Builder()
        .name("target-range")
        .description("A cuántos bloques se busca un objetivo. Clasificar en fase es otra cosa y va a 10 fijo: "
            + "ningún módulo dirigido llega más lejos, así que un objetivo más allá de 10 se ve y se informa, "
            + "pero no enciende nada.")
        .defaultValue(16)
        .range(4, 64)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Integer> approachDistance = sgGeneral.add(new IntSetting.Builder()
        .name("approach-distance")
        .description("Frontera entre acercamiento y superficie, con una banda de un bloque a cada lado: "
            + "se entra en ACERCAMIENTO por encima de esta distancia más 1 y se vuelve a SUPERFICIE por debajo "
            + "de esta menos 1, para que un objetivo parado justo en el umbral no haga oscilar la fase. "
            + "Tope en 6: EntityUtils.getCityBlock() de Meteor no ve rodeado más allá de esa distancia, "
            + "y un approach-distance mayor dejaría una franja donde nunca se detecta RODEADO. Suelo en 2: por debajo "
            + "de 3 ya no hay fase que pida el aura, y de 3 al rango de cristal (4,5) la mantiene encendida la cuenta "
            + "de hostiles, no la fase.")
        .defaultValue(6)
        .range(2, 6)
        .sliderRange(2, 6)
        .build()
    );

    /**
     * El umbral del eje defensivo (rediseño §5). El núcleo lo deja abierto y como ajuste; su
     * constante {@link com.xploits.pvp.core.DefensivePolicy#THREAT_MARGIN} es solo el valor de
     * fábrica, y es el que se pone aquí.
     */
    private final Setting<Double> threatMargin = sgGeneral.add(new DoubleSetting.Builder()
        .name("threat-margin")
        .description("Cuánta vida te tiene que quedar, descontando el daño que YA te apunta (cristales puestos, "
            + "alguien con espada pegado a ti, camas en el Nether, la caída), para seguir tranquilo. Por debajo "
            + "se encienden hole-filler, anti-anvil, anti-bed y anti-anchor, y además surround si estás en un "
            + "agujero, pisando suelo y con la altura quieta -mientras tu Y se mueva, surround se apagaría solo-. "
            + "No es 'estoy bajo de vida': es lo que ya está colocado contra ti. "
            + "Subirlo salta antes y cuesta poco -ninguno de esos módulos te inmoviliza y solo hole-filler gasta-; "
            + "bajarlo te deja reaccionar más tarde.")
        .defaultValue(DefensivePolicy.THREAT_MARGIN)
        .range(0, 40)
        .sliderRange(0, 20)
        .build()
    );

    /**
     * CUIDADO: este ajuste escribe en configuración de Meteor que no es del addon. Su descripción lo
     * dice con todas las letras porque el jugador tiene que enterarse desde la ClickGUI, sin leer
     * ningún README (spec §14.3).
     */
    private final Setting<Boolean> syncFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("sync-friends")
        .description("ESCRIBE EN TU LISTA DE AMIGOS DE METEOR (.friends), que no es del addon: "
            + "mientras auto-pvp esté encendido añade ahí a los couriers de kit-requester y a la lista users "
            + "de auto-tpy, y los quita al apagarlo. Es la única forma de que crystal-aura, auto-trap, "
            + "auto-web, auto-anvil y auto-city -que eligen su propio objetivo, y solo miran esa lista- "
            + "tampoco les ataquen. Solo quita lo que añadió él: a un amigo que ya tuvieras no lo toca nunca, "
            + "y si le quitas uno de los suyos a mano no lo vuelve a poner. Con trust-unknown-couriers "
            + "encendido NO sincroniza ningún courier, porque entonces cualquiera que imite un READY entra "
            + "solo en esa lista (spec §14.2).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify")
        .description("Aviso local al cambiar de fase ofensiva o de postura defensiva, al no encender algo que "
            + "la situación pedía, al no atacar a uno de los nuestros y al tocar tu lista de amigos de Meteor. "
            + "Cada aviso se dice una vez, cuando aparece, no en cada tick. Los dos avisos de fallo silencioso "
            + "-SIN_RECURSOS y 'lleva rato encendido sin gastar nada'- se dicen siempre, lo apagues o no: son "
            + "justo los que no se pueden ver de ninguna otra manera.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifySound = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-sound")
        .description("Sonido en el aviso fuerte de SIN_RECURSOS.")
        .defaultValue(true)
        .build()
    );

    private final CombatDirector director = new CombatDirector();
    private final ModuleLedger ledger = new ModuleLedger();

    /**
     * El vigilante de "lo tengo encendido y no hace nada" ({@link ActionWatch}). Vive aquí y no en
     * el director porque necesita un dato que el director no ve: qué módulos están encendidos <b>de
     * verdad</b> ahora mismo. El adaptador mide -lo que pide el plan, lo que está encendido y lo
     * que queda en la hotbar- y el núcleo decide cuándo eso ha dejado de ser un hueco de combate.
     */
    private final ActionWatch actionWatch = new ActionWatch();
    private final FriendLedger friendLedger = new FriendLedger();

    /**
     * Lo último que se sincronizó con la lista de amigos, o {@code null} si todavía no se ha
     * reconciliado en esta activación. Mientras no cambie no se lee la lista de amigos ni se escribe
     * un solo byte en disco (spec §14.1).
     */
    private Set<String> lastSynced;

    private Plan lastPlan;
    private CombatSnapshot lastSnapshot;
    private String lastTargetName;
    private Double lastTargetDistance;
    private CombatState lastReported = CombatState.SIN_COMBATE;
    private CombatPosture lastPosture = CombatPosture.TRANQUILO;
    /**
     * Si tu Y cambió en el tick <b>anterior</b> (rediseño §5, crítico C2). Se guarda porque
     * {@code Surround} comprueba {@code prevY != getY()} en {@code TickEvent.Pre} y este módulo mide
     * en {@code TickEvent.Post}: lo que el módulo castigará al principio del tick siguiente es el
     * movimiento que aquí se ve al final de este. Juntando los dos ticks, la postura deja de pedir
     * {@code surround} en cualquiera de los dos y el autoapagado no llega a dispararse nunca
     * mientras el director lo quiera encendido.
     */
    private boolean yChangedLastTick;
    private SkippedAlly skippedAlly;
    /** Nombres de los nuestros ya anunciados en esta activación: cada uno se dice una sola vez. */
    private final Set<String> announcedAllies = new LinkedHashSet<>();
    /**
     * Los avisos y las omisiones que ya estaban dichos el tick anterior. Es lo que convierte "esto
     * pasa veinte veces por segundo" en una línea de chat: solo se dice lo que aparece, y solo
     * cuando aparece. Las dos fuentes están protegidas río arriba -la histéresis de recursos del
     * núcleo para las omisiones, la cuenta de cristales para el aviso del aura-, así que una entrada
     * que entra y sale no rebota.
     */
    private Set<String> announcedNotes = Set.of();

    public AutoPvp() {
        super(XploitsAddon.CATEGORY, "auto-pvp", "Dirige los módulos de combate según la fase de la pelea.");
    }

    @Override
    public void onActivate() {
        director.reset();
        ledger.reset();
        actionWatch.reset();
        lastPlan = null;
        lastSnapshot = null;
        lastTargetName = null;
        lastTargetDistance = null;
        lastReported = CombatState.SIN_COMBATE;
        lastPosture = CombatPosture.TRANQUILO;
        yChangedLastTick = false;
        skippedAlly = null;
        announcedAllies.clear();
        announcedNotes = Set.of();
        // La lista de amigos no se toca aquí: la primera reconciliación es la del primer tick, que
        // ya exige estar en el mundo. Encender el módulo desde la ClickGUI en el menú principal no
        // escribe nada en el disco del jugador.
        friendLedger.reset();
        lastSynced = null;
        warnAlreadyActiveManagedModules();
    }

    @Override
    public void onDeactivate() {
        releaseAll();
        // Lo que pusimos en la lista de amigos sale con nosotros, y solo lo que pusimos nosotros.
        applyFriendChanges(friendLedger.release(meteorFriendNames()));
        friendLedger.reset();
        lastSynced = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || !mc.player.isAlive()) {
            releaseAll();
            return;
        }

        // Las dos listas de origen, recortadas una sola vez por tick: las usan el filtro de objetivo
        // -una vez por jugador a la vista- y la sincronización con los amigos de Meteor.
        Set<String> couriers = AllyPolicy.names(kitRequesterCouriers());
        Set<String> tpyUsers = AllyPolicy.names(autoTpyUsers());
        syncMeteorFriends(couriers, tpyUsers);

        PlayerEntity target = findTarget(couriers, tpyUsers);
        lastTargetName = target == null ? null : nameOf(target);

        CombatSnapshot snapshot = snapshot(target, couriers, tpyUsers);
        lastSnapshot = snapshot;
        lastTargetDistance = snapshot.hasTarget() ? snapshot.targetDistance() : null;
        Plan plan = director.tick(snapshot, approachDistance.get(), threatMargin.get());
        lastPlan = plan;

        apply(plan);
        reportPhaseChange(plan);
        reportPostureChange(plan);
        reportNotes(plan);
        reportSkippedAlly();
        lastReported = plan.state();
        lastPosture = plan.posture();
    }

    /** Uno de los nuestros que estaba a tiro y no se atacó: quién, por qué y a qué distancia. */
    private record SkippedAlly(String name, AllyPolicy.Allegiance allegiance, double distance) {}

    /**
     * El objetivo, con los nuestros descartados <b>dentro</b> del predicado de selección (spec §13).
     *
     * <p>Es el mismo predicado que {@code TargetUtils.getPlayerTarget(range, priority)} —verificado
     * contra las fuentes de meteor-client 1.21.11—, con dos cambios: {@code Friends.shouldAttack}
     * pasa a estar dentro de {@link AllyPolicy} (AMIGO es exactamente su negación, así que el filtro
     * de Meteor se sigue aplicando igual) y se añaden los couriers de kit-requester y la lista
     * users de auto-tpy.
     *
     * <p>CRÍTICO: la exclusión tiene que ir en el predicado, no después. Seleccionar el más cercano
     * y descartarlo si resulta ser nuestro devolvería {@code null} con un courier pegado a ti,
     * aunque hubiera un enemigo de verdad diez bloques detrás; dentro del predicado el courier ni
     * siquiera entra en la lista que se ordena, y el enemigo sigue siendo el objetivo.
     *
     * <p>Esto decide a quién elige <b>este</b> módulo, y solo eso. Los cinco que enciende eligen su
     * propio objetivo y no saben de estas listas: de que a ellos tampoco se les cruce un courier se
     * encarga {@link #syncMeteorFriends} (spec §14).
     */
    private PlayerEntity findTarget(Set<String> couriers, Set<String> tpyUsers) {
        double range = targetRange.get();
        skippedAlly = null;

        Entity found = TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player) || entity == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            if (!PlayerUtils.isWithin(entity, range)) return false;

            AllyPolicy.Allegiance allegiance =
                AllyPolicy.of(nameOf(player), Friends.get().isFriend(player), couriers, tpyUsers);
            if (allegiance.isOurs()) {
                noteSkippedAlly(player, allegiance);
                return false;
            }

            if (entity instanceof FakePlayerEntity fakePlayer) return !fakePlayer.noHit;
            return EntityUtils.getGameMode(player) == GameMode.SURVIVAL;
        }, SortPriority.LowestDistance);

        return found instanceof PlayerEntity player ? player : null;
    }

    /** Se queda con el más cercano de los nuestros descartados en este tick. */
    private void noteSkippedAlly(PlayerEntity player, AllyPolicy.Allegiance allegiance) {
        double distance = mc.player.distanceTo(player);
        if (skippedAlly == null || distance < skippedAlly.distance()) {
            skippedAlly = new SkippedAlly(nameOf(player), allegiance, distance);
        }
    }

    /** Que se vea, pero sin llenar el chat: cada nombre se dice una sola vez por activación. */
    private void reportSkippedAlly() {
        if (skippedAlly == null || !notify.get()) return;
        // Cota: con la lista llena se empieza de cero y se vuelve a avisar, preferible a crecer sin
        // fin en una sesión larga. El vaciado va ANTES del add: vaciar después de apuntar el nombre
        // lo borraba en el mismo momento de anunciarlo, y el aliado que tocase el tope se anunciaba
        // dos veces, la segunda en cuanto volviera a estar a tiro.
        if (announcedAllies.size() >= MAX_ANNOUNCED_ALLIES) announcedAllies.clear();
        if (!announcedAllies.add(skippedAlly.name())) return;
        info("No ataco a %s: %s.", skippedAlly.name(), skippedAlly.allegiance().reason());
    }

    private static String nameOf(PlayerEntity player) {
        return player.getGameProfile().name();
    }

    /**
     * Los couriers de kit-requester y la lista users de auto-tpy, <b>estén esos módulos encendidos
     * o apagados</b>. Es a propósito distinto de {@code AutoTpy.kitRequesterCouriers()}, que sí
     * exige {@code isActive()}: allí la pregunta es de reparto —quién responde a esta TPA—, y si
     * kit-requester está apagado nadie más va a responderla; aquí la pregunta es de identidad —de
     * quién eres—, y el courier que llegó con un pedido anterior sigue pegado a ti después de que
     * kit-requester se apague. Condicionarlo al módulo devolvería el ataque en silencio, que es
     * exactamente el fallo que esto arregla.
     */
    private static Set<String> kitRequesterCouriers() {
        KitRequester module = Modules.get().get(KitRequester.class);
        return module == null ? Set.of() : module.knownCouriers();
    }

    private static Set<String> autoTpyUsers() {
        AutoTpy module = Modules.get().get(AutoTpy.class);
        return module == null ? Set.of() : module.users();
    }

    /** Si kit-requester acepta couriers desconocidos, que es lo que hace su lista poco fiable (spec §14.2). */
    private static boolean kitRequesterTrustsUnknownCouriers() {
        KitRequester module = Modules.get().get(KitRequester.class);
        return module != null && module.trustsUnknownCouriers();
    }

    /**
     * Mantiene a los nuestros en la lista de amigos de Meteor (spec §14). Es lo que hace que los
     * cinco módulos dirigidos —que eligen su propio objetivo y solo respetan esa lista— tampoco
     * ataquen a un courier o a alguien de la lista users.
     *
     * <p><b>Se reconcilia por cambio, no por tick.</b> {@code Friends.add} y {@code Friends.remove}
     * guardan {@code friends.nbt} en cada llamada (verificado en las fuentes: las dos llaman a
     * {@code save()}), así que reconciliar a ciegas veinte veces por segundo sería escribir en el
     * disco del jugador veinte veces por segundo. Mientras el conjunto que hay que sincronizar sea
     * el mismo que la última vez no se hace nada: ni se recorre la lista de amigos, ni se escribe.
     * Cambia cuando el jugador edita {@code known-couriers} o {@code users}, cuando kit-requester
     * aprende un courier, cuando se toca {@code trust-unknown-couriers} o {@code sync-friends}, y en
     * el primer tick de cada activación.
     */
    private void syncMeteorFriends(Set<String> couriers, Set<String> tpyUsers) {
        Set<String> wanted = syncFriends.get()
            ? FriendLedger.syncable(couriers, kitRequesterTrustsUnknownCouriers(), tpyUsers)
            : Set.of();
        if (wanted.equals(lastSynced)) return;
        lastSynced = wanted;
        applyFriendChanges(friendLedger.reconcile(wanted, meteorFriendNames()));
    }

    /** Los nombres que hay ahora mismo en la lista de amigos, tal y como los guarda Meteor. */
    private static Set<String> meteorFriendNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Friend friend : Friends.get()) names.add(friend.getName());
        return names;
    }

    /**
     * Ejecuta lo que decidió {@link FriendLedger}. Un añadido que Meteor rechaza deja de contar como
     * nuestro en el acto; y antes de quitar nada se comprueba que el amigo que devuelve
     * {@code Friends.get(String)} —que compara con {@code equalsIgnoreCase}— es exactamente el
     * nombre que pusimos nosotros. De lo contrario bastaría que el jugador tuviera un
     * "stormaegis44" suyo para que le borrásemos su entrada al soltar la nuestra.
     */
    private void applyFriendChanges(FriendLedger.Result result) {
        if (result.isEmpty()) return;

        Friends friends = Friends.get();
        List<String> added = new ArrayList<>();
        for (String name : result.toAdd()) {
            if (friends.add(new Friend(name))) added.add(name);
            else friendLedger.disown(name);
        }

        List<String> removed = new ArrayList<>();
        for (String name : result.toRemove()) {
            Friend friend = friends.get(name);
            if (friend != null && name.equals(friend.getName()) && friends.remove(friend)) removed.add(name);
        }

        if (!notify.get()) return;
        if (!added.isEmpty()) {
            info("Añado a tus amigos de Meteor: %s. Así los otros cinco módulos de combate tampoco les atacan.",
                String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            info("Quito de tus amigos de Meteor: %s. Los había puesto yo.", String.join(", ", removed));
        }
    }

    /** Avisa del cambio de fase: SIN_RECURSOS siempre y fuerte (spec §4.1, §6); el resto, si notify lo permite. */
    private void reportPhaseChange(Plan plan) {
        if (plan.state() == lastReported) return;

        if (plan.state() == CombatState.SIN_RECURSOS) {
            warnOutOfResources(plan);
        } else if (notify.get()) {
            info("%s%s", plan.state(), lastTargetName == null ? "" : " · " + lastTargetName);
        }
    }

    /**
     * Avisa del otro eje (rediseño §3). Va en su propia línea, y no pegado a la fase, porque son
     * ortogonales: la postura cambia sin que la fase se mueva -te cristalean mientras sigues en
     * SUPERFICIE- y al revés. Solo se dice cuando cambia, que con el umbral de §5 son un par de
     * líneas por pelea, no veinte por segundo.
     */
    private void reportPostureChange(Plan plan) {
        if (plan.posture() == lastPosture || !notify.get()) return;

        if (plan.posture() == CombatPosture.AMENAZADO) {
            info("AMENAZADO · %s de daño ya te apunta y te quedan %s de vida.",
                number(lastSnapshot.incomingDamage()), number(lastSnapshot.selfTotalHealth()));
        } else {
            info("TRANQUILO · ya no hay nada colocado que te deje bajo el margen.");
        }
    }

    /**
     * Dice una sola vez lo que el plan no va a encender y por qué, y los avisos que no son
     * omisiones (rediseño §7: el aura sin cristales se enciende igual, pero el jugador tiene que
     * enterarse). Sin esta memoria serían veinte líneas por segundo; con ella, una por cosa nueva.
     *
     * <p>Queda fuera el aviso fuerte de {@code SIN_RECURSOS}, que tiene su propio camino y suena
     * aunque {@code notify} esté apagado.
     */
    private void reportNotes(Plan plan) {
        // La clave de una omisión es el nombre del módulo, NO su motivo: el motivo lleva dentro
        // cuánto te queda ("tienes 2, necesita 8"), y eso baja con cada bloque que gastas, así que
        // comparar motivos enteros volvería a ser una línea por tick. Lo que el jugador necesita
        // saber es que auto-trap no va a subir, no el número exacto de este tick -que sí sale, y
        // actualizado, en .xploits pvp-.
        Map<String, String> notes = new LinkedHashMap<>();
        for (String warning : plan.warnings()) notes.put(warning, warning);
        for (Skipped skipped : plan.skipped()) {
            notes.put(skipped.module().name(),
                "No enciendo " + skipped.module().name() + ": " + skipped.reason() + ".");
        }

        if (notify.get() && plan.state() != CombatState.SIN_RECURSOS) {
            for (Map.Entry<String, String> note : notes.entrySet()) {
                if (!announcedNotes.contains(note.getKey())) warning("%s", note.getValue());
            }
        }
        announcedNotes = Set.copyOf(notes.keySet());
    }

    /** Un número de vida o de daño como se lee en español, con un decimal. */
    private static String number(double value) {
        return String.format(Locale.forLanguageTag("es"), "%.1f", value);
    }

    private CombatSnapshot snapshot(PlayerEntity target, Set<String> couriers, Set<String> tpyUsers) {
        Inventory inventory = inventory();
        // La mitad tuya del snapshot se lee siempre, haya objetivo o no: el eje defensivo (§5) se
        // deriva de ti y no depende de que el director haya elegido a alguien. Son los mismos dos
        // datos que usan AutoTotem, Offhand y AutoLog para decidir lo mismo, y el cliente los sabe
        // en todos los servidores: getTotalHealth() es vida + absorción y possibleHealthReductions()
        // es el daño que YA te apunta (cristales colocados, jugadores con espada a <=5, camas en el
        // Nether y caída). isInHole(false) es el agujero sin dobles, el único sitio del surround.
        double totalHealth = PlayerUtils.getTotalHealth();
        double incomingDamage = PlayerUtils.possibleHealthReductions();
        boolean inHole = PlayerUtils.isInHole(false);
        boolean onGround = mc.player.isOnGround();
        boolean antiSuicide = crystalAuraAntiSuicide();
        int hostiles = hostilesInCrystalRange(couriers, tpyUsers);
        // La misma comparación que hace Surround en su toggle-on-y-change -field_6036 es lastY en
        // yarn 1.21.11+build.3, comprobado en las mappings, no supuesto-, más la del tick anterior:
        // el módulo la evalúa en TickEvent.Pre y aquí se mide en Post, así que el movimiento que le
        // hará apagarse al principio del tick que viene es el que se ve al final de este, y con los
        // dos ticks juntos la postura no lo pide en ninguno de los dos.
        boolean movedNow = mc.player.lastY != mc.player.getY();
        boolean yChanged = movedNow || yChangedLastTick;
        yChangedLastTick = movedNow;

        if (target == null) {
            return new CombatSnapshot(false, 0, 0, 0, false, false,
                mc.player.isGliding(), inventory.totems(), inventory.resources(),
                null, hostiles, totalHealth, incomingDamage, inHole, onGround, yChanged, antiSuicide);
        }

        // RODEADO exige el alcance real de auto-city al bloque, no al objetivo (spec §4.2.1,
        // corregido): el bloque es un vecino horizontal del objetivo y puede caer al lado contrario
        // de donde estás tú. Se mide exactamente como lo hace AutoCity.java de Meteor
        // (PlayerUtils.squaredDistanceTo contra la BlockPos, a la esquina mínima del bloque, no al
        // centro) para que la comparación en el núcleo sea la misma que auto-city aplicará después.
        BlockPos cityBlock = EntityUtils.getCityBlock(target);
        double cityBlockDistance = cityBlock != null ? Math.sqrt(PlayerUtils.squaredDistanceTo(cityBlock)) : 0;
        return new CombatSnapshot(true, mc.player.distanceTo(target),
            surroundSides(target), cityBlockDistance, protectedFromCrystals(target), target.isGliding(),
            mc.player.isGliding(), inventory.totems(), inventory.resources(),
            nameOf(target), hostiles, totalHealth, incomingDamage, inHole, onGround, yChanged, antiSuicide);
    }

    /**
     * Cuántos de los cuatro vecinos horizontales del objetivo, a la altura de sus pies, son de los
     * que {@code EntityUtils.getCityBlock()} considera minables (menor M1).
     *
     * <p>El director usaba {@code getCityBlock(target) != null} como "tiene surround", y no lo es:
     * verificado en las fuentes de {@code meteor-client:1.21.11-SNAPSHOT}, ese método recorre las
     * cuatro direcciones horizontales y devuelve <b>la más cercana</b> que sea de esta lista, o
     * {@code null}; nunca cuenta cuántas hay. Un enemigo de pie junto al muro de obsidiana de
     * cualquier base -o junto a la obsidiana que tu propio {@code auto-trap} acaba de colocar- daba
     * bloque, clasificaba {@code RODEADO} y el director se ponía a minar la pared.
     *
     * <p>La lista es la misma que la de Meteor, y no incluye bedrock (spec §2): obsidiana, bloque
     * de netherita, obsidiana llorosa, ancla de reaparición y escombros antiguos. Cuántos lados
     * hacen falta lo decide el núcleo ({@code CombatDirector.SURROUND_MIN_SIDES}), que es donde se
     * puede probar; aquí solo se cuenta.
     */
    private int surroundSides(PlayerEntity target) {
        BlockPos feet = target.getBlockPos();
        int sides = 0;
        for (Direction direction : Direction.values()) {
            if (direction.getAxis().isVertical()) continue;
            if (isCityBlock(mc.world.getBlockState(feet.offset(direction)).getBlock())) sides++;
        }
        return sides;
    }

    /** Los cinco bloques que {@code EntityUtils.getCityBlock()} acepta, verificados en sus fuentes. */
    private static boolean isCityBlock(Block block) {
        return block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN
            || block == Blocks.NETHERITE_BLOCK || block == Blocks.RESPAWN_ANCHOR
            || block == Blocks.ANCIENT_DEBRIS;
    }

    /**
     * ¿Le protege un cristal de lo que hay en sus pies? (rediseño §4.1). Es la pregunta que decide
     * {@code ENTERRADO}, y no es la que se hacía antes.
     *
     * <p>Antes se preguntaba {@code blocksMovement()}, que es "¿hay algo sólido ahí?". En el
     * bytecode de 1.21.11 eso es {@code !COBWEB && !BAMBOO_SAPLING && isSolid()}, y {@code isSolid()}
     * solo exige un lado medio de 0,7291666666666666: una losa inferior da 0,833 y pasaba. Es decir
     * que estar de pie sobre una losa, una escalera, un cofre o una trampilla se clasificaba
     * ENTERRADO, el director apagaba el aura y se plantaba a poner yunques contra alguien que no
     * estaba protegido de nada.
     *
     * <p>La pregunta correcta tiene dos mitades y las dos hacen falta:
     * <ul>
     *   <li><b>Resistencia a explosiones &ge; 600</b> ({@code Block#getBlastResistance()}). Es el
     *       umbral que ya usa {@code PlayerUtils.isInHole(boolean)} de Meteor para decidir si un
     *       bloque te protege, así que la clasificación y lo que Meteor considera un agujero dicen
     *       lo mismo. La obsidiana, la obsidiana llorosa, el bloque de netherita, los escombros
     *       antiguos y el bedrock lo cumplen; la piedra, la tierra y la telaraña no.</li>
     *   <li><b>Cubo completo</b> ({@code AbstractBlockState#isFullCube}, que es
     *       {@code Block.isShapeFullCube(getCollisionShape(...))}). Sin esto, bloques con 1200 de
     *       resistencia y forma parcial -una mesa de encantamientos, un ancla a medio uso- darían
     *       por enterrado a quien esté de pie encima, que es el mismo fallo de la losa por la otra
     *       puerta.</li>
     * </ul>
     *
     * <p>Los dos nombres están comprobados con {@code javap} sobre el jar de Minecraft remapeado de
     * este proyecto (yarn 1.21.11+build.3), no supuestos.
     */
    private boolean protectedFromCrystals(PlayerEntity player) {
        BlockPos pos = player.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);
        return state.getBlock().getBlastResistance() >= PROTECTIVE_BLAST_RESISTANCE
            && state.isFullCube(mc.world, pos);
    }

    /**
     * Cuántos hostiles hay a rango de cristal, <b>protegidos o no</b> (rediseño §4.4, corregido por
     * el crítico C1). Es lo que impide el cebo obvio: uno se entierra, el otro te cristalea, y el
     * director te apaga el aura contra el segundo porque la fase es de un solo jugador.
     *
     * <p>El filtro es el mismo que el de {@link #findTarget} —los nuestros fuera, supervivencia,
     * vivos—, con una sola diferencia: el rango es el de cristal, no el {@code target-range} del
     * módulo.
     *
     * <p><b>Ya no se descarta al protegido</b>, y ese descarte era el crítico. Preguntaba "¿puedo yo
     * cristalear a alguien?" para decidir "¿necesito el aura?", que son dos preguntas distintas: el
     * aura coloca y rompe, y romper no gasta ningún cristal tuyo (§2). Que el otro esté enterrado o
     * rodeado le salva a él de tus cristales; no te salva a ti de los suyos. Con el descarte puesto,
     * un enemigo que se entierra a 3,5 y te sigue cristaleando desde dentro del burrow hacía que la
     * cuenta fuera cero y el ledger te apagaba el autobreak contra el único que podía matarte.
     *
     * <p>Con él se va también el uso de {@code EntityUtils.getCityBlock()} para esto, que además
     * agravaba el fallo: ese método no comprueba si alguien tiene surround, sino si hay un bloque
     * minable pegado a él, así que un enemigo junto a un muro de obsidiana -o junto a la que tu
     * propio {@code auto-trap} acababa de colocar- contaba como "protegido" y desaparecía de la
     * cuenta.
     */
    private int hostilesInCrystalRange(Set<String> couriers, Set<String> tpyUsers) {
        int hostiles = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isDead() || player.getHealth() <= 0) continue;
            if (!PlayerUtils.isWithin(player, CombatDirector.CRYSTAL_RANGE)) continue;
            if (AllyPolicy.of(nameOf(player), Friends.get().isFriend(player), couriers, tpyUsers).isOurs()) continue;
            if (player instanceof FakePlayerEntity fakePlayer) {
                if (fakePlayer.noHit) continue;
            } else if (EntityUtils.getGameMode(player) != GameMode.SURVIVAL) {
                continue;
            }
            hostiles++;
        }
        return hostiles;
    }

    /**
     * Si el {@code anti-suicide} de {@code crystal-aura} está encendido (rediseño §7, por la puerta
     * de §10). Es lo único que decide si el suelo de tótems sigue en pie.
     *
     * <p>Se lee por el API público de ajustes de Meteor —{@code Module.settings} es
     * {@code public final} y {@code Settings#get(String, Class)} devuelve el {@code Setting<Boolean>}
     * ya tipado, comparando el nombre sin distinguir mayúsculas—, no por reflexión ni por un mixin:
     * el campo {@code antiSuicide} de {@code CrystalAura} es privado, pero el ajuste no.
     *
     * <p>Si el módulo no está cargado o el ajuste no aparece, se responde <b>apagado</b>, que es el
     * valor prudente: sin poder demostrar que la protección existe, el suelo de tótems se queda.
     */
    private static boolean crystalAuraAntiSuicide() {
        Module crystalAura = byName(ManagedModules.CRYSTAL_AURA.name());
        if (crystalAura == null) return false;
        Setting<Boolean> antiSuicide = crystalAura.settings.get("anti-suicide", Boolean.class);
        return antiSuicide != null && antiSuicide.get();
    }

    /** Jugadores cargados y cuántos son de los tuyos, con la misma definición que el objetivo. Funciona con auto-pvp apagado. */
    public record Vecindario(int cargados, int nuestros) {
    }

    public Optional<Vecindario> vecindario() {
        if (mc.world == null || mc.player == null) return Optional.empty();
        Set<String> couriers = AllyPolicy.names(kitRequesterCouriers());
        Set<String> tpyUsers = AllyPolicy.names(autoTpyUsers());
        int cargados = 0;
        int nuestros = 0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            cargados++;
            if (AllyPolicy.of(nameOf(player), Friends.get().isFriend(player), couriers, tpyUsers).isOurs()) nuestros++;
        }
        return Optional.of(new Vecindario(cargados, nuestros));
    }

    /** La munición de la hotbar, que es la que usan los módulos que dirige. El pico no es munición. */
    public Optional<Map<Resource, Integer>> recursosEnBarra() {
        if (mc.player == null) return Optional.empty();
        Map<Resource, Integer> recursos = new EnumMap<>(Resource.class);
        recursos.putAll(inventory().resources());
        recursos.remove(Resource.PICKAXE);
        return Optional.of(recursos);
    }

    @Override
    public String ahora() {
        if (lastPlan == null) return "leyendo";
        return lastPlan.state() + (lastTargetName == null ? "" : " · " + lastTargetName);
    }

    /** Lo que se lee del inventario para el snapshot: un solo barrido de los 36 slots para todo. */
    private record Inventory(Map<Resource, Integer> resources, int totems) {}

    private Inventory inventory() {
        Map<Resource, Integer> counts = new EnumMap<>(Resource.class);
        int totems = mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING ? 1 : 0;
        for (int slot = FIRST_SLOT; slot <= INVENTORY_LAST_SLOT; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) { totems++; continue; }
            Resource resource = resourceOf(stack);
            if (resource == null) continue;
            // CRÍTICO (spec §6): ningún recurso cuenta fuera de la hotbar. Los cinco módulos que
            // buscan con InvUtils.findInHotbar/testInHotbar ya lo exigen porque no miran más lejos;
            // auto-city busca el pico con InvUtils.find sobre todo el inventario, pero rechaza el
            // resultado si no está en la hotbar (FindItemResult.isHotbar()) y se apaga solo con un
            // error. Contar la mochila para el pico diría "tomados" a un módulo que se apaga solo
            // en su propio onActivate/onTick.
            if (slot > HOTBAR_LAST_SLOT) continue;
            counts.merge(resource, stack.getCount(), Integer::sum);
        }
        return new Inventory(counts, totems);
    }

    private static Resource resourceOf(ItemStack stack) {
        if (stack.getItem() == Items.END_CRYSTAL) return Resource.CRYSTALS;
        if (stack.getItem() == Items.OBSIDIAN) return Resource.OBSIDIAN;
        if (stack.getItem() == Items.COBWEB) return Resource.WEBS;
        if (stack.getItem() == Items.ANVIL) return Resource.ANVILS;
        if (stack.getItem() == Items.NETHERITE_PICKAXE || stack.getItem() == Items.DIAMOND_PICKAXE) return Resource.PICKAXE;
        return null;
    }

    /**
     * Enciende lo que pide el plan y apaga lo que tomó y ya no pide; nunca lo que no es suyo (spec
     * §7). La decisión de propiedad es de {@link ModuleLedger}, lógica pura y con tests propios
     * (spec §12): aquí solo se reúnen los nombres reales y se ejecuta lo que decide.
     */
    private void apply(Plan plan) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ManagedModule module : plan.enable()) wanted.add(module.name());

        Set<String> active = new LinkedHashSet<>();
        for (ManagedModule module : ManagedModules.ALL) {
            Module m = byName(module.name());
            if (m != null && m.isActive()) active.add(module.name());
        }

        // El vigilante mide ANTES de ejecutar nada: `active` es lo que está encendido de verdad al
        // principio de este tick, que es lo que hay que cruzar con lo que el plan quiere. Un módulo
        // recién encendido no cuenta hasta el tick siguiente, y eso es lo correcto: todavía no ha
        // tenido ocasión de gastar.
        reportIdle(actionWatch.update(lastSnapshot, wanted, active));

        ModuleLedger.Result result = ledger.apply(director.state(), plan.posture(), wanted, active);

        for (String name : result.toDisable()) {
            Module module = byName(name);
            if (module != null) module.disable();
        }
        for (String name : result.toEnable()) {
            Module module = byName(name);
            if (module != null) module.enable();
        }
        if (notify.get()) {
            for (String name : result.newlyReleased()) {
                info("%s ya no es mío: lo apagaste tú y no lo vuelvo a tomar en esta fase.", name);
            }
        }
    }

    /**
     * Dice lo que el vigilante acaba de concluir ({@link ActionWatch}). <b>Se dice aunque
     * {@code notify} esté apagado</b>, por la misma razón que {@code SIN_RECURSOS}: los dos son
     * fallos que no se pueden ver de ninguna otra manera -el módulo anuncia la fase, enciende el
     * aura y no pasa nada-, y callarlos es exactamente lo que el principio del módulo prohíbe. Los
     * avisos normales -cambios de fase, de postura, omisiones- sí respetan el ajuste: esos se ven
     * de sobra por sus efectos.
     *
     * <p>No apaga nada. El aviso es el final del camino: quien decide si {@code min-damage} está
     * mal puesto es el jugador, y mientras tanto el módulo sigue listo para el tick en el que sí
     * haya posición válida.
     */
    private void reportIdle(List<ActionWatch.Idle> newlyIdle) {
        for (ActionWatch.Idle idle : newlyIdle) warning("%s", ActionWatch.reason(idle));
    }

    /** I1: si algo que dirige ya estaba encendido al activar auto-pvp, es del jugador y hay que decirlo. */
    private void warnAlreadyActiveManagedModules() {
        for (ManagedModule managed : ManagedModules.ALL) {
            Module module = byName(managed.name());
            if (module == null || !module.isActive()) continue;

            if (managed.equals(ManagedModules.CRYSTAL_AURA)) {
                warning("crystal-aura ya estaba encendido: es tuyo, no lo apagaré ni contra un enterrado.");
            } else {
                warning("%s ya estaba encendido: es tuyo, no lo tocaré mientras no lo sueltes tú.", managed.name());
            }
        }
    }

    private void releaseAll() {
        for (String name : ledger.owned()) {
            Module module = byName(name);
            if (module != null && module.isActive()) module.disable();
        }
        ledger.reset();
        director.reset();
        actionWatch.reset();
        lastPlan = null;
        lastSnapshot = null;
        lastReported = CombatState.SIN_COMBATE;
        lastPosture = CombatPosture.TRANQUILO;
        yChangedLastTick = false;
        skippedAlly = null;
        announcedAllies.clear();
        announcedNotes = Set.of();
    }

    private static Module byName(String name) {
        return Modules.get().get(name);
    }

    /** I5: SIN_RECURSOS es el único aviso fuerte (spec §4.1, §6): chat en warning() y toast con sonido. */
    private void warnOutOfResources(Plan plan) {
        String message = outOfResourcesMessage(plan);
        warning("%s", message);

        MeteorToast.Builder toast = new MeteorToast.Builder("Xploits").text(message).icon(Items.BARRIER);
        // MeteorToast.update() llama a play(customSound) sin comprobar el nulo y vanilla lo dereferencia:
        // NPE en el hilo de render. Nunca pasar null; se silencia con volumen cero, igual que ElytraReplace.
        if (!notifySound.get()) {
            toast.sound(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 0f));
        }
        mc.getToastManager().add(toast.build());
    }

    private static String outOfResourcesMessage(Plan plan) {
        String reasons = plan.skipped().stream()
            .map(skipped -> skipped.module().name() + " (" + skipped.reason() + ")")
            .collect(Collectors.joining(", "));
        if (reasons.isEmpty()) return "Sin recursos: no hay ningún módulo de esta fase que puedas sostener.";
        return "Sin recursos para pelear: " + reasons + ".";
    }

    public String status() {
        if (!isActive()) return "auto-pvp está apagado.";
        if (lastPlan == null) return "auto-pvp encendido, todavía sin leer la situación.";

        Set<String> owned = ledger.owned();

        // Los dos ejes, en la primera línea y en este orden (rediseño §3): la fase la impone el
        // enemigo y la postura eres tú, y las dos son verdad a la vez. Debajo, el detalle de cada
        // una, para que se vea de un vistazo por qué está encendido lo que está encendido.
        StringBuilder sb = new StringBuilder();
        sb.append(lastPlan.state()).append(" desde hace ").append(director.ticksInState() / TICKS_PER_SECOND).append(" s");
        sb.append(" · ").append(lastPlan.posture());
        if (lastTargetName != null) {
            sb.append(" · objetivo ").append(lastTargetName);
            if (lastTargetDistance != null) {
                sb.append(" a ").append(number(lastTargetDistance)).append(" bloques");
            }
        }
        if (lastSnapshot != null) {
            sb.append("\n  tú:           ").append(number(lastSnapshot.selfTotalHealth()))
                .append(" de vida con ").append(number(lastSnapshot.incomingDamage()))
                .append(" de daño ya apuntándote (margen ").append(number(threatMargin.get())).append(")");
            if (lastSnapshot.selfInHole()) sb.append(", en un agujero");
            if (lastSnapshot.selfGliding()) sb.append(", planeando");
            int hostiles = lastSnapshot.hostilesInCrystalRange();
            if (hostiles > 0) {
                sb.append("\n  cristales:    ").append(hostiles)
                    .append(hostiles == 1 ? " hostil" : " hostiles")
                    .append(" a rango de cristal (el aura se queda encendida por ellos, pase lo que pase con la fase: ")
                    .append("estén protegidos o no, sus cristales te entran igual y romper no te cuesta ninguno)");
            }
        }
        if (skippedAlly != null) {
            sb.append("\n  no ataco:     ").append(skippedAlly.name())
                .append(" — ").append(skippedAlly.allegiance().reason())
                .append(", a ").append(number(skippedAlly.distance()))
                .append(" bloques");
        }
        sb.append("\n  en amigos:    ").append(syncedFriendsLine());
        sb.append("\n  tomados:      ").append(owned.isEmpty() ? "ninguno" : String.join(", ", owned));
        sb.append("\n  sin gastar:   ").append(idleLine());
        for (Skipped skipped : lastPlan.skipped()) {
            sb.append("\n  no encendido: ").append(skipped.module().name()).append(" — ").append(skipped.reason());
        }
        for (String warning : lastPlan.warnings()) {
            sb.append("\n  aviso:        ").append(warning);
        }
        List<String> yours = yourActiveModules(owned);
        sb.append("\n  tuyos:        ").append(yours.isEmpty() ? "ninguno" : String.join(", ", yours)).append(" (no los toco)");
        return sb.toString();
    }

    /**
     * Qué módulos llevan rato encendidos sin gastar nada ({@link ActionWatch}), y cuánto rato. Es la
     * otra mitad del aviso: el aviso se dice una vez, pero la situación dura -un {@code min-damage}
     * mal puesto no se cura solo- y tiene que poder consultarse mientras dura.
     *
     * <p>La línea sale siempre, aunque no haya ninguno, porque decir "ninguno" también es
     * información: significa que lo que está encendido está gastando.
     *
     * <p>Los que comparten pila salen juntos y marcados como veredicto conjunto, que es lo único
     * que la medida sostiene: el inventario dice cuánta obsidiana queda, no quién la colocó.
     */
    private String idleLine() {
        List<ActionWatch.Idle> idle = actionWatch.idle();
        if (idle.isEmpty()) return "ninguno (lo que está encendido está gastando)";

        List<String> parts = new ArrayList<>();
        for (ActionWatch.Idle verdict : idle) {
            List<String> names = new ArrayList<>();
            for (ManagedModule module : verdict.modules()) names.add(module.name());
            // El "+" no es decorativo: dice que esos nombres van juntos porque comparten pila y el
            // veredicto no se puede repartir entre ellos.
            String joint = verdict.joint()
                ? ", veredicto conjunto: los " + names.size() + " comparten pila y no sé cuál falla"
                : "";
            parts.add(String.join(" + ", names)
                + " (" + verdict.ticks() / TICKS_PER_SECOND + " s" + joint + ")");
        }
        return String.join(", ", parts) + " — encendidos, con enemigo delante y material de sobra";
    }

    /**
     * Qué está tocando ahora mismo de la lista de amigos de Meteor, y si no toca nada, por qué: el
     * jugador tiene que poder ver en una línea que su configuración global está intervenida —o que
     * no lo está, y entonces los otros cinco módulos sí pueden atacar a los nuestros (spec §14.3).
     */
    private String syncedFriendsLine() {
        if (!syncFriends.get()) {
            return "sincronización apagada — los otros cinco módulos de combate sí pueden atacarles";
        }
        Set<String> synced = friendLedger.added();
        if (synced.isEmpty()) {
            return kitRequesterTrustsUnknownCouriers()
                ? "ninguno (con trust-unknown-couriers encendido no se sincroniza ningún courier)"
                : "ninguno";
        }
        return String.join(", ", synced) + " (los puse yo en tu lista de Meteor y los quitaré al apagarme)";
    }

    /** I6: qué módulos de combate llevas activos que el director no controla, no una lista fija. */
    private List<String> yourActiveModules(Set<String> owned) {
        List<String> result = new ArrayList<>();
        for (ManagedModule managed : ManagedModules.ALL) {
            if (owned.contains(managed.name())) continue;
            Module module = byName(managed.name());
            if (module != null && module.isActive()) result.add(managed.name());
        }
        for (String name : ALWAYS_YOURS) {
            Module module = byName(name);
            if (module != null && module.isActive()) result.add(name);
        }
        return result;
    }
}
