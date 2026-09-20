package com.xploits.kitrequester.inventory;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
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

    private enum Phase { IDLE, OPENING, MOVING }

    private Phase phase = Phase.IDLE;
    private long openedAt;
    private long movingSince;

    /** El ender chest que se pidió abrir en {@link #start}. Fija a qué contenedor está atada la operación. */
    private BlockPos targetPos;
    /**
     * Último bloque con el que se ha interactuado -propio o del jugador-, alimentado por
     * {@link #onInteractBlock}. Mismo mecanismo que {@code StashKeeper.candidate}: el problema es
     * el mismo (una pantalla que se abre no dice por sí sola qué bloque la respalda) y esta es la
     * única señal disponible para atarla a una interacción concreta.
     */
    private BlockPos candidate;
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
            double distance = eyes.distanceTo(Vec3d.ofCenter(pos));
            if (distance <= REACH && distance < bestDistance) {
                best = pos.toImmutable();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Abre el ender chest al alcance más cercano. Devuelve false sin mandar nada si no hay ninguno
     * o si el jugador ya tiene una pantalla abierta: mandar el interact ahí no sirve de nada -el
     * servidor ya tiene una ventana abierta para él- y es exactamente el primer paso del fallo que
     * vacía shulkers en cualquier contenedor que el jugador tuviera abierto a mano.
     */
    public boolean start(MinecraftClient mc, long now) {
        if (mc.player == null) return false;
        if (!(mc.player.currentScreenHandler instanceof PlayerScreenHandler)) return false;
        Optional<BlockPos> target = findInReach(mc);
        if (target.isEmpty()) return false;
        BlockPos pos = target.get();
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
        targetPos = pos;
        candidate = pos;
        syncId = null;
        phase = Phase.OPENING;
        openedAt = now;
        return true;
    }

    /**
     * Se alimenta de {@code InteractBlockEvent}, que dispara tanto esta misma interacción como
     * cualquier clic del jugador en otro contenedor mientras se espera la respuesta del servidor
     * (spec: ventana de {@link #OPEN_TIMEOUT_MS}). Es la única manera de saber, cuando una pantalla
     * aparece, si el bloque que la respalda es el ender chest que se pidió o algo que el jugador
     * abrió por su cuenta.
     */
    public void onInteractBlock(BlockPos pos) {
        candidate = pos;
    }

    /** Llamar en cada tick mientras OrderMachine está en DEPOSIT. */
    public Optional<Boolean> tick(MinecraftClient mc, long now) {
        if (phase != Phase.IDLE && mc.player == null) {
            phase = Phase.IDLE;
            return Optional.of(false);
        }
        switch (phase) {
            case IDLE -> {
                return Optional.of(false);
            }
            case OPENING -> {
                if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler) {
                    // La pantalla que acaba de aparecer solo se acepta si la última interacción
                    // vista coincide con el ender chest pedido. Si no coincide -el jugador abrió
                    // otra cosa mientras esperábamos-, ante la duda no se toca nada (spec §6).
                    if (targetPos.equals(candidate)) {
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
        candidate = null;
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
