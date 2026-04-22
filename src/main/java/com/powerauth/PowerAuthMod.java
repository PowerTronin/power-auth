package com.powerauth;

import com.powerauth.auth.AuthManager;
import com.powerauth.auth.AuthMessages;
import com.powerauth.auth.PowerAuthConfig;
import com.powerauth.auth.TelegramBotService;
import com.powerauth.auth.UserStore;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class PowerAuthMod implements ModInitializer {
    public static final String MOD_ID = "power-auth";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static AuthManager authManager;
    private static TelegramBotService telegramBotService;
    private static Path configPath;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(PowerAuthMod::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (telegramBotService != null) {
                telegramBotService.stop();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> authManager.handleJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> authManager.handleDisconnect(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(server -> authManager.tick(server));

        registerProtectionEvents();
        registerCommands();
    }

    public static AuthManager auth() {
        return authManager;
    }

    private static void start(MinecraftServer server) {
        Path configDir = server.getRunDirectory().toPath().resolve("config");
        configPath = configDir.resolve("power-auth.json");
        PowerAuthConfig config = PowerAuthConfig.load(configPath);
        UserStore userStore = new UserStore(server.getRunDirectory().toPath().resolve("power-auth-users.json"));
        authManager = new AuthManager(config, userStore);
        if (config.telegram.enabled) {
            telegramBotService = new TelegramBotService(config.telegram, authManager, server);
            telegramBotService.start();
        }
        LOGGER.info("Power Auth initialized.");
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("register")
                .then(argument("password", StringArgumentType.word())
                    .then(argument("repeat", StringArgumentType.word())
                        .executes(ctx -> register(ctx.getSource(), StringArgumentType.getString(ctx, "password"), StringArgumentType.getString(ctx, "repeat"))))));

            dispatcher.register(literal("login")
                .then(argument("password", StringArgumentType.word())
                    .executes(ctx -> login(ctx.getSource(), StringArgumentType.getString(ctx, "password")))));

            dispatcher.register(literal("auth")
                .then(argument("code", StringArgumentType.word())
                    .executes(ctx -> authCode(ctx.getSource(), StringArgumentType.getString(ctx, "code")))));

            dispatcher.register(literal("linktg")
                .executes(ctx -> linkTelegram(ctx.getSource())));

            dispatcher.register(literal("2fa")
                .then(literal("on").executes(ctx -> set2fa(ctx.getSource(), true)))
                .then(literal("off").executes(ctx -> set2fa(ctx.getSource(), false)))
                .then(literal("status").executes(ctx -> telegramStatus(ctx.getSource()))));

            dispatcher.register(literal("authadmin")
                .requires(source -> authManager != null && source.hasPermissionLevel(authManager.adminPermissionLevel()))
                .then(literal("info")
                    .then(argument("player", StringArgumentType.word())
                        .executes(ctx -> adminResult(ctx.getSource(), authManager.adminInfo(StringArgumentType.getString(ctx, "player"))))))
                .then(literal("resetpassword")
                    .then(argument("player", StringArgumentType.word())
                        .executes(ctx -> adminResult(ctx.getSource(), authManager.adminResetPassword(StringArgumentType.getString(ctx, "player"))))))
                .then(literal("reset2fa")
                    .then(argument("player", StringArgumentType.word())
                        .executes(ctx -> adminResult(ctx.getSource(), authManager.adminReset2fa(StringArgumentType.getString(ctx, "player"))))))
                .then(literal("stats")
                    .executes(ctx -> adminResult(ctx.getSource(), authManager.adminStats())))
                .then(literal("reload")
                    .executes(ctx -> reload(ctx.getSource()))));
        });
    }

    private static int register(ServerCommandSource source, String password, String repeat) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AuthMessages.send(player, authManager.register(player, password, repeat));
        return 1;
    }

    private static int login(ServerCommandSource source, String password) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AuthMessages.send(player, authManager.login(player, password));
        return 1;
    }

    private static int authCode(ServerCommandSource source, String code) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AuthMessages.send(player, authManager.verifyTelegramCode(player, code));
        return 1;
    }

    private static int linkTelegram(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AuthMessages.send(player, authManager.createTelegramLinkCode(player));
        return 1;
    }

    private static int set2fa(ServerCommandSource source, boolean enabled) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AuthMessages.send(player, authManager.setTelegram2fa(player, enabled));
        return 1;
    }

    private static int telegramStatus(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AuthMessages.send(player, authManager.telegramStatus(player));
        return 1;
    }

    private static int reload(ServerCommandSource source) {
        PowerAuthConfig config = PowerAuthConfig.load(configPath);
        authManager.reloadConfig(config);
        if (telegramBotService != null) {
            telegramBotService.stop();
            telegramBotService = null;
        }
        if (config.telegram.enabled) {
            telegramBotService = new TelegramBotService(config.telegram, authManager, source.getServer());
            telegramBotService.start();
        }
        source.sendFeedback(() -> Text.literal("Power Auth config reloaded."), true);
        return 1;
    }

    private static int adminResult(ServerCommandSource source, AuthMessages.Result result) {
        if (result.success()) {
            source.sendFeedback(() -> Text.literal(result.message()), true);
        } else {
            source.sendError(Text.literal(result.message()));
        }
        return result.success() ? 1 : 0;
    }

    private static void registerProtectionEvents() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
            isLocked(player) ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
            isLocked(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
            isLocked(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
            isLocked(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
            isLocked(player) ? TypedActionResult.fail(player.getStackInHand(hand)) : TypedActionResult.pass(player.getStackInHand(hand)));
    }

    private static boolean isLocked(net.minecraft.entity.player.PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || authManager == null) {
            return false;
        }
        if (authManager.isAuthenticated(serverPlayer)) {
            return false;
        }
        serverPlayer.sendMessage(Text.literal("Сначала войди: /login <пароль> или /register <пароль> <пароль>"), true);
        return true;
    }
}
