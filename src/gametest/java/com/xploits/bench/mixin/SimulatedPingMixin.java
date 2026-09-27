package com.xploits.bench.mixin;

import com.xploits.bench.core.PingDelay;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.local.LocalChannel;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;

/**
 * Bench-only (R3-12, gametest source set: never on the addon's own classpath, see
 * {@code xploits-bench.mixins.json}). Delays every packet the integrated server's local connection carries, in
 * both directions, so a crystal-aura MEASURE plays out over a simulated ping instead of the integrated server's
 * lock-step tick race (research-offense-gap: the server and the client tick in the same millisecond, so a few
 * microseconds of client work decide whether a placement lands this tick or the next; a real network makes that
 * race irrelevant).
 *
 * <p>{@code ClientConnection.addFlowControlHandler} runs once for the client's own connection (side
 * CLIENTBOUND, whose pipeline carries what the server sends) and once for the server's connection to that same
 * player (side SERVERBOUND, what the client sends): every packet already goes through the normal pipeline
 * (splitter, decoder, {@code packet_handler}), keep-alives included, so adding the same delay to both call sites
 * gives a symmetric round trip with no special case for any one packet type (requirement 3). It is added before
 * {@code packet_handler} (an inject at {@code HEAD}, before this method's own {@code addLast} calls), so it runs
 * before the connection processes the packet, and only for a {@link LocalChannel} (the integrated server's
 * connection; never a real one) while {@link PingDelay#ACTIVE_PROPERTY} names a positive number of milliseconds,
 * which the bench sets fresh before every run's world is created ({@code BenchTest.runOnce}) and which is 0 (no
 * handler at all, the pipeline exactly as vanilla builds it) unless the scenario plays over the simulated ping
 * ({@code Scenario#simulatesPing}).
 *
 * <p>Each delayed message is rescheduled on the channel's own event-loop executor with the same fixed delay
 * (no jitter): {@code AbstractScheduledEventExecutor} breaks a tie between two equal deadlines by a monotonic
 * submission id, so messages that arrived in order are always re-fired in that same order, and none is ever
 * dropped, only delayed (requirement 2).
 *
 * <p><b>Verified: needs Fabric's own network synchronizer disabled too.</b> The client-gametest framework
 * blocks each frame until every packet it saw sent has been handled on the netty thread within 10 s
 * ({@code NetworkSynchronizer.waitForPacketHandlers}), an assumption this mixin breaks on purpose. Left on,
 * the synchronizer eventually logs "Detected interfacing with packets at a lower level" and crashes the
 * client with "Network synchronizer in invalid state" (seen on a real run). {@code build.gradle.kts} sets
 * {@code -Dfabric.client.gametest.disableNetworkSynchronizer=true} for every {@code clientGameTest} run,
 * which is the fix Fabric's own log line names.
 */
@Mixin(ClientConnection.class)
abstract class SimulatedPingMixin {
    @Inject(method = "addFlowControlHandler", at = @At("HEAD"))
    private void xploits$delay(ChannelPipeline pipeline, CallbackInfo ci) {
        Channel channel = pipeline.channel();
        if (!(channel instanceof LocalChannel)) return;
        int delayMs = Integer.getInteger(PingDelay.ACTIVE_PROPERTY, 0);
        if (delayMs <= 0) return;
        pipeline.addLast("xploits-bench-delay", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.executor().schedule(() -> ctx.fireChannelRead(msg), delayMs, TimeUnit.MILLISECONDS);
            }
        });
    }
}
