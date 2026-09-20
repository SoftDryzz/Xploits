package com.xploits.kitrequester.inventory;

import com.xploits.kitrequester.core.CandidateTracker;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/** Vacía shulkers en un ender chest al alcance (spec §6). Mueve uno por tick y nunca camina. */
public final class EnderDepositor {
    public static final double REACH = 4.5;

    private static final int SCAN_RADIUS = 5;
    private static final long OPEN_TIMEOUT_MS = 5_000;
    private static final long MOVE_TIMEOUT_MS = 10_000;
    /**
     * Ventana en la que un candidato -de bloque o de entidad, propio o ajeno- se considera vigente
     * (spec §6.1, tercera corrección). Igual a {@link #OPEN_TIMEOUT_MS} a propósito: antes eran
     * independientes (1 s frente a 5 s), y un pico de lag de 6b6t podía caducar el candidato
     * mientras {@code start()} seguía dentro de su propia ventana de espera, dejando pasar
     * exactamente la carrera que este campo existe para bloquear. Con las dos ventanas iguales eso
     * ya no puede pasar: si {@code start()} sigue esperando su pantalla, el candidato tampoco ha
     * caducado.
     *
     * <p>Solo la usa {@link #start}: caduca el candidato de bloque justo antes de comprobar
     * {@link CandidateTracker#blocksStart}. {@code tick()} no caduca nada -el candidato ahí solo
     * importa para la comprobación puntual de {@link CandidateTracker#matches} en {@code OPENING},
     * que no depende de esta ventana-.
     */
    private static final long CANDIDATE_TIMEOUT_MS = OPEN_TIMEOUT_MS;

    private enum Phase { IDLE, OPENING, MOVING }

    private Phase phase = Phase.IDLE;
    private long openedAt;
    private long movingSince;

    /** El ender chest que se pidió abrir en {@link #start}. Fija a qué contenedor está atada la operación. */
    private BlockPos targetPos;
    /**
     * Candidato y caducidad (spec §6.1): qué interacción -propia o ajena, de bloque o de entidad- se
     * vio más recientemente. La lógica es pura y vive en {@code kitrequester/core}; aquí solo se
     * traduce {@link BlockPos} a {@code long} y se decide, con Minecraft, qué bloque de verdad puede
     * respaldar la pantalla que se espera ({@link #opensContainerScreen}).
     */
    private final CandidateTracker tracker = new CandidateTracker(CANDIDATE_TIMEOUT_MS);
    /** syncId del handler ya aceptado como el ender chest de esta operación. Null hasta OPENING→MOVING. */
    private Integer syncId;

    public static Optional<BlockPos> findInReach(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return Optional.empty();
        BlockPos center = mc.player.getBlockPos();
        Vec3d eyes = mc.player.getEyePos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS),
                                             center.add(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS))) {
            if (!mc.world.getBlockState(pos).isOf(Blocks.ENDER_CHEST)) continue;
            // Un bloque sólido encima impide abrirlo: verificado en el bytecode de
            // EnderChestBlock.onUse -si world.getBlockState(pos.up()).isSolidBlock(...) es cierto,
            // devuelve ActionResult.SUCCESS sin abrir ninguna pantalla-. Pedir su apertura de todos
            // modos no produce ningún error visible, solo un OPEN_TIMEOUT_MS entero perdido sin
            // motivo (spec §6, corregido).
            BlockPos above = pos.up();
            if (mc.world.getBlockState(above).isSolidBlock(mc.world, above)) continue;
            double distance = eyes.distanceTo(Vec3d.ofCenter(pos));
            if (distance <= REACH && distance < bestDistance) {
                best = pos.toImmutable();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Abre el ender chest al alcance más cercano. Devuelve false sin mandar nada si no hay ninguno,
     * si el jugador ya tiene una pantalla abierta, o si hay una interacción ajena reciente sin
     * resolver -un clic (de bloque o de entidad) que el jugador acaba de mandar y cuya pantalla
     * todavía no ha llegado ({@link #CANDIDATE_TIMEOUT_MS})-: mandar el interact en cualquiera de
     * esos casos es exactamente el primer paso del fallo que vacía shulkers en cualquier contenedor
     * que el jugador tuviera abierto o a punto de abrirse (spec §6.1).
     */
    public boolean start(MinecraftClient mc, long now) {
        if (mc.player == null) return false;
        if (!(mc.player.currentScreenHandler instanceof PlayerScreenHandler)) return false;
        tracker.expire(now);
        // Mientras la fase es IDLE, un candidato vigente solo puede venir de onInteractBlock o
        // onInteractEntity -esta misma llamada nunca lo deja puesto en IDLE-, así que si sigue
        // vigente es una interacción del jugador cuya pantalla todavía no ha llegado del servidor.
        // Empezar ahora es la carrera exacta del fallo: la pantalla ajena llega después, con el
        // candidato ya reescrito al ender chest, y OPENING la acepta como si fuera la suya.
        if (tracker.blocksStart(now)) return false;
        Optional<BlockPos> target = findInReach(mc);
        if (target.isEmpty()) return false;
        BlockPos pos = target.get();
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
        targetPos = pos;
        tracker.noteBlockInteraction(pos.asLong(), true, now);
        syncId = null;
        phase = Phase.OPENING;
        openedAt = now;
        return true;
    }

    /**
     * Se alimenta de {@code InteractBlockEvent}, que dispara tanto esta misma interacción como
     * cualquier clic del jugador en otro contenedor mientras se espera la respuesta del servidor
     * (spec: ventana de {@link #OPEN_TIMEOUT_MS}). Es una de las dos señales para saber, cuando una
     * pantalla aparece, si el bloque que la respalda es el ender chest que se pidió o algo que el
     * jugador abrió por su cuenta -la otra es {@link #onInteractEntity}, para vagonetas y barcas-.
     *
     * <p>Se ignora si el bloque no es de un tipo que pueda respaldar una pantalla de contenedor
     * -colocar un bloque, abrir una puerta, o los {@code BlockUtils.place} de
     * {@code surround}/{@code auto-trap} no deben poder abortar un depósito en curso ni bloquear el
     * siguiente (spec §6.1, punto 2)-.
     */
    public void onInteractBlock(MinecraftClient mc, BlockPos pos, long now) {
        if (mc.world == null) return;
        tracker.noteBlockInteraction(pos.asLong(), opensContainerScreen(mc.world.getBlockState(pos).getBlock()), now);
    }

    /**
     * Se alimenta de {@code InteractEntityEvent} (mixin de {@code interactEntity}, dispara para
     * cualquier clic del jugador en una entidad). Los cofres de vagoneta ({@code ChestMinecartEntity})
     * y de barca ({@code AbstractChestBoatEntity}) abren una {@code GenericContainerScreenHandler}
     * 9x3, igual que cualquier cofre de bloque -verificado en el bytecode-, pero
     * {@code InteractBlockEvent} nunca los ve porque no son bloques: sin este gancho, un clic en
     * cualquiera de los dos pasaba desapercibido y {@link #start} podía pisarlo igual que antes de
     * la primera corrección pisaba un cofre de bloque (spec §6.1, tercera corrección).
     *
     * <p>No hay {@code BlockPos} que anotar como candidato -una entidad no tiene uno-, así que esto
     * solo alimenta a {@link #start}: "algo ajeno puede estar en vuelo". La confirmación positiva del
     * título en {@code OPENING} ({@link #isEnderChestScreen}) es quien de verdad protege esa fase.
     */
    public void onInteractEntity(long now) {
        tracker.noteEntityInteraction(now);
    }

    /**
     * Bloques que abren una pantalla de contenedor, igual que {@code StashKeeper.typeOf}: cofre,
     * cofre trampa, barril y ender chest respaldan una {@code GenericContainerScreenHandler}; el
     * shulker box respalda su propia {@code ShulkerBoxScreenHandler} -no la genérica, verificado en
     * el bytecode de {@code ShulkerBoxBlockEntity.createScreenHandler}-, pero de todas formas es un
     * contenedor que el jugador puede abrir a mano mientras se espera al ender chest, así que cuenta
     * igual como candidato.
     */
    private static boolean opensContainerScreen(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
            || block == Blocks.ENDER_CHEST
            || (block.asItem() != null && Utils.isShulker(block.asItem()));
    }

    /**
     * Confirmación positiva de que la pantalla abierta es de verdad un ender chest, no una deducción
     * por candidato (spec §6.1, tercera corrección). Los cofres de vagoneta y de barca son entidades
     * y abren la misma {@code GenericContainerScreenHandler} 9x3 que un ender chest, así que el
     * candidato por sí solo ya no basta como prueba.
     *
     * <p>Verificado en el bytecode de {@code EnderChestBlock.onUse}: abre con
     * {@code new SimpleNamedScreenHandlerFactory(..., CONTAINER_NAME)}, donde
     * {@code CONTAINER_NAME = Text.translatable("container.enderchest")}, y ese mismo {@code Text}
     * es el que {@code ClientPlayNetworkHandler.onOpenScreen} pasa a {@code HandledScreens.open(...)}
     * -en la misma llamada síncrona que fija {@code mc.player.currentScreenHandler}, sin ningún tick
     * de por medio- y acaba en {@link HandledScreen#getTitle()}.
     *
     * <p>Se compara el texto ya traducido ({@code getString()}), no las claves de traducción: el
     * título de la pantalla usa {@code container.enderchest} y {@code Blocks.ENDER_CHEST.getName()}
     * usa {@code block.minecraft.ender_chest} -claves distintas-, pero producen el mismo texto en
     * todos los idiomas comprobados contra los ficheros de Mojang (en_us, es_es, fr_fr, de_de,
     * ru_ru, zh_cn, ja_jp, pt_br, it_it). Un servidor vanilla no puede cambiar ese título: lo fija
     * {@code EnderChestBlock}, sin pasar por ningún dato que el servidor controle.
     */
    private static boolean isEnderChestScreen(MinecraftClient mc) {
        return mc.currentScreen instanceof HandledScreen<?> screen
            && screen.getTitle().getString().equals(Blocks.ENDER_CHEST.getName().getString());
    }

    /** Llamar en cada tick mientras OrderMachine está en DEPOSIT. */
    public Optional<Boolean> tick(MinecraftClient mc, long now) {
        if (phase != Phase.IDLE && mc.player == null) {
            reset();
            return Optional.of(false);
        }
        switch (phase) {
            case IDLE -> {
                return Optional.of(false);
            }
            case OPENING -> {
                if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
                    // Ya no basta con que el candidato coincida con el BlockPos pedido -eso es una
                    // deducción, y los cofres de vagoneta/barca no generan InteractBlockEvent-: se
                    // exige además que el título de la pantalla sea de verdad el de un ender chest
                    // (spec §6.1, tercera corrección). Si cualquiera de las dos falla, ante la duda
                    // no se toca nada.
                    if (tracker.matches(targetPos.asLong()) && isEnderChestScreen(mc)) {
                        syncId = handler.syncId;
                        phase = Phase.MOVING;
                        movingSince = now;
                    } else {
                        return finish(mc, false);
                    }
                } else if (now - openedAt > OPEN_TIMEOUT_MS) return finish(mc, false);
                return Optional.empty();
            }
            case MOVING -> {
                if (now - movingSince > MOVE_TIMEOUT_MS) return finish(mc, false);
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return finish(mc, false);
                // El contenedor pudo cerrarse y abrirse otro distinto entre dos ticks sin pasar
                // nunca por PlayerScreenHandler (p. ej. el servidor encadena dos ventanas). El
                // syncId es la única forma de notar que ya no es la ventana que se aceptó en OPENING.
                if (syncId == null || handler.syncId != syncId) return finish(mc, false);
                int containerSlots = handler.getRows() * 9; // 27 o 54: en 6b6t el ender chest puede ser doble
                if (!hasEmptySlot(handler, containerSlots)) return finish(mc, true);
                for (int i = containerSlots; i < handler.slots.size(); i++) {
                    Slot slot = handler.slots.get(i);
                    if (Utils.isShulker(slot.getStack().getItem())) {
                        mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                        return Optional.empty();
                    }
                }
                return finish(mc, true);
            }
        }
        return Optional.empty();
    }

    /**
     * Cierra la pantalla del ender chest si el depositor la abrió, y vuelve a IDLE. Seguro llamar
     * en cualquier momento. Solo cierra una pantalla que se haya confirmado como la propia
     * ({@code syncId} atado en OPENING→MOVING): una pantalla ajena que se rechazó en OPENING nunca
     * llega a tener {@code syncId} y no se toca, para no cerrarle al jugador algo que abrió él.
     */
    public void reset() {
        if (syncId != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler
                && handler.syncId == syncId) {
                mc.player.closeHandledScreen();
            }
        }
        phase = Phase.IDLE;
        targetPos = null;
        tracker.clear();
        syncId = null;
    }

    private Optional<Boolean> finish(MinecraftClient mc, boolean ok) {
        reset();
        return Optional.of(ok);
    }

    private static boolean hasEmptySlot(GenericContainerScreenHandler handler, int containerSlots) {
        for (int i = 0; i < containerSlots; i++) {
            if (handler.slots.get(i).getStack().isEmpty()) return true;
        }
        return false;
    }
}
