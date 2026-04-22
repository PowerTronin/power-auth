package com.powerauth.auth;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class AuthMessages {
    private AuthMessages() {
    }

    public static void send(ServerPlayerEntity player, Result result) {
        player.sendMessage(Text.literal(result.message()).formatted(result.success() ? Formatting.GREEN : Formatting.RED), false);
    }

    public static Result ok(String message) {
        return new Result(true, message);
    }

    public static Result error(String message) {
        return new Result(false, message);
    }

    public record Result(boolean success, String message) {
    }
}
