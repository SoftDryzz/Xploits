package com.xploits.pvp;

import com.xploits.XploitsAddon;
import com.xploits.autotpy.AutoTpy;
import com.xploits.kitrequester.KitRequester;
import com.xploits.pvp.core.AllyPolicy;
import com.xploits.pvp.core.CombatDirector;
import com.xploits.pvp.core.CombatSnapshot;
import com.xploits.pvp.core.CombatState;
import com.xploits.pvp.core.FriendLedger;
import com.xploits.pvp.core.ManagedModule;
import com.xploits.pvp.core.ManagedModules;
import com.xploits.pvp.core.ModuleLedger;
import com.xploits.pvp.core.Plan;
import com.xploits.pvp.core.Resource;
import com.xploits.pvp.core.Skipped;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dirige los módulos de combate de Meteor según la fase de la pelea (spec §1). No ejecuta ninguna
 * acción de combate: solo enciende y apaga, y solo apaga lo que encendió él (spec §7).
 */
public class AutoPvp extends Module {
    private static final int FIRST_SLOT = 0;
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

    /** Módulos de combate que auto-pvp nunca toca, los lleves encendidos o no (spec §7). */
    private static final List<String> ALWAYS_YOURS = List.of("auto-totem", "auto-armor", "offhand", "auto-weapon");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> targetRange = sgGeneral.add(new IntSetting.Builder()
        .name("target-range")
        .description("A cuántos bloques se busca un objetivo.")
        .defaultValue(16)
        .range(4, 64)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Integer> approachDistance = sgGeneral.add(new IntSetting.Builder()
        .name("approach-distance")
        .description("Más lejos de esta distancia la fase es de acercamiento; más cerca, de superficie. "
            + "Tope en 6: EntityUtils.getCityBlock() de Meteor no ve rodeado más allá de esa distancia (spec §4.2), "
            + "y un approach-distance mayor dejaría una franja donde nunca se detecta RODEADO.")
        .defaultValue(6)
        .range(2, 6)
        .sliderRange(2, 6)
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
        .description("Aviso local al cambiar de fase, al no atacar a uno de los nuestros y al tocar tu lista "
            + "de amigos de Meteor. SIN_RECURSOS avisa siempre, lo apagues o no (spec §4.1).")
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
    private final FriendLedger friendLedger = new FriendLedger();

    /**
     * Lo último que se sincronizó con la lista de amigos, o {@code null} si todavía no se ha
     * reconciliado en esta activación. Mientras no cambie no se lee la lista de amigos ni se escribe
     * un solo byte en disco (spec §14.1).
     */
    private Set<String> lastSynced;

    private Plan lastPlan;
    private String lastTargetName;
    private Double lastTargetDistance;
    private CombatState lastReported = CombatState.SIN_COMBATE;
    private SkippedAlly skippedAlly;
    /** Nombres de los nuestros ya anunciados en esta activación: cada uno se dice una sola vez. */
    private final Set<String> announcedAllies = new LinkedHashSet<>();

    public AutoPvp() {
        super(XploitsAddon.CATEGORY, "auto-pvp", "Dirige los módulos de combate según la fase de la pelea.");
    }

    @Override
    public void onActivate() {
        director.reset();
        ledger.reset();
        lastPlan = null;
        lastTargetName = null;
        lastTargetDistance = null;
        lastReported = CombatState.SIN_COMBATE;
        skippedAlly = null;
        announcedAllies.clear();
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

        CombatSnapshot snapshot = snapshot(target);
        lastTargetDistance = snapshot.hasTarget() ? snapshot.targetDistance() : null;
        Plan plan = director.tick(snapshot, approachDistance.get());
        lastPlan = plan;

        apply(plan);
        reportPhaseChange(plan);
        reportSkippedAlly();
        lastReported = plan.state();
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

    @SuppressWarnings("deprecation") // Uso de AbstractBlock.AbstractBlockState#blocksMovement, igual que EntityUtils.isAboveWater en meteor-client
    private CombatSnapshot snapshot(PlayerEntity target) {
        Inventory inventory = inventory();
        if (target == null) {
            return new CombatSnapshot(false, 0, false, 0, false, false,
                mc.player.isGliding(), inventory.totems(), inventory.resources());
        }
        // "Enterrado" exige que el bloque bloquee el movimiento, no solo que no sea aire (spec
        // §4.3): !isAir() también es verdadero con agua, hierba alta, nieve, alfombras o
        // carteles, y sobre todo con la telaraña que pone auto-web en esta misma posición -que es
        // exactamente el módulo que RODEADO/SUPERFICIE encienden justo antes-. blocksMovement() lo
        // distingue: la obsidiana y el bedrock de un burrow lo cumplen, la telaraña no (verificado
        // contra AbstractBlock.AbstractBlockState#blocksMovement en las fuentes de Yarn 1.21.11:
        // excluye COBWEB explícitamente y en general solo es true para bloques "solid").
        boolean burrowed = mc.world.getBlockState(target.getBlockPos()).blocksMovement();
        // RODEADO exige el alcance real de auto-city al bloque, no al objetivo (spec §4.2.1,
        // corregido): el bloque es un vecino horizontal del objetivo y puede caer al lado contrario
        // de donde estás tú. Se mide exactamente como lo hace AutoCity.java de Meteor
        // (PlayerUtils.squaredDistanceTo contra la BlockPos, a la esquina mínima del bloque, no al
        // centro) para que la comparación en el núcleo sea la misma que auto-city aplicará después.
        BlockPos cityBlock = EntityUtils.getCityBlock(target);
        double cityBlockDistance = cityBlock != null ? Math.sqrt(PlayerUtils.squaredDistanceTo(cityBlock)) : 0;
        return new CombatSnapshot(true, mc.player.distanceTo(target),
            cityBlock != null, cityBlockDistance, burrowed, target.isGliding(),
            mc.player.isGliding(), inventory.totems(), inventory.resources());
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

        ModuleLedger.Result result = ledger.apply(director.state(), wanted, active);

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
        lastPlan = null;
        lastReported = CombatState.SIN_COMBATE;
        skippedAlly = null;
        announcedAllies.clear();
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

        StringBuilder sb = new StringBuilder();
        sb.append(lastPlan.state()).append(" desde hace ").append(director.ticksInState() / 20L).append(" s");
        if (lastTargetName != null) {
            sb.append(" · objetivo ").append(lastTargetName);
            if (lastTargetDistance != null) {
                sb.append(" a ").append(String.format(Locale.forLanguageTag("es"), "%.1f", lastTargetDistance)).append(" bloques");
            }
        }
        if (skippedAlly != null) {
            sb.append("\n  no ataco:     ").append(skippedAlly.name())
                .append(" — ").append(skippedAlly.allegiance().reason())
                .append(", a ").append(String.format(Locale.forLanguageTag("es"), "%.1f", skippedAlly.distance()))
                .append(" bloques");
        }
        sb.append("\n  en amigos:    ").append(syncedFriendsLine());
        sb.append("\n  tomados:      ").append(owned.isEmpty() ? "ninguno" : String.join(", ", owned));
        for (Skipped skipped : lastPlan.skipped()) {
            sb.append("\n  no encendido: ").append(skipped.module().name()).append(" — ").append(skipped.reason());
        }
        List<String> yours = yourActiveModules(owned);
        sb.append("\n  tuyos:        ").append(yours.isEmpty() ? "ninguno" : String.join(", ", yours)).append(" (no los toco)");
        return sb.toString();
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
