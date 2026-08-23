package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.movement.BoatFlightFeature;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class BoatFlightConnectionMixin {
    @Inject(
        method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void celeste$observeOrHoldBoatFlightPacket(Packet<?> packet, ChannelFutureListener listener,
                                                        boolean flush, CallbackInfo callbackInfo) {
        BoatFlightFeature.observeOutgoingPacket(packet);
        if (BoatFlightFeature.shouldBlinkHold(packet)) {
            callbackInfo.cancel();
        }
    }
}
