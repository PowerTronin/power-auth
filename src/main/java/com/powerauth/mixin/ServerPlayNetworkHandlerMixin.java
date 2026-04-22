package com.powerauth.mixin;

import com.powerauth.PowerAuthMod;
import com.powerauth.auth.AuthManager;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onCommandExecution", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockCommands(CommandExecutionC2SPacket packet, CallbackInfo ci) {
        AuthManager auth = PowerAuthMod.auth();
        if (auth != null && !auth.isAuthenticated(player) && !auth.isAllowedLockedCommand(packet.command())) {
            player.sendMessage(Text.literal("Сначала войди в аккаунт."), true);
            ci.cancel();
        }
    }

    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockChat(ChatMessageC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            player.sendMessage(Text.literal("Чат доступен после входа."), true);
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockInteractItem(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockInteractEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockInventoryClick(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            ci.cancel();
        }
    }

    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void powerAuth$blockCreativeInventory(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        if (isLocked()) {
            ci.cancel();
        }
    }

    private boolean isLocked() {
        AuthManager auth = PowerAuthMod.auth();
        return auth != null && !auth.isAuthenticated(player);
    }
}
