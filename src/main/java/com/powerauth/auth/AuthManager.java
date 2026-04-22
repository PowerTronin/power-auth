package com.powerauth.auth;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PowerAuthConfig config;
    private final UserStore userStore;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingLogin> pendingLogins = new ConcurrentHashMap<>();
    private final Map<String, PendingTelegramLink> pendingTelegramLinks = new ConcurrentHashMap<>();
    private final Map<UUID, AttemptState> passwordAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, AttemptState> telegramAttempts = new ConcurrentHashMap<>();

    public AuthManager(PowerAuthConfig config, UserStore userStore) {
        this.config = config;
        this.userStore = userStore;
    }

    public boolean isAuthenticated(ServerPlayerEntity player) {
        return authenticated.contains(player.getUuid());
    }

    public int adminPermissionLevel() {
        return config.adminPermissionLevel;
    }

    public void reloadConfig(PowerAuthConfig config) {
        this.config = config;
    }

    public boolean isAllowedLockedCommand(String command) {
        String root = command.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return config.allowedCommandsWhileLocked.contains(root);
    }

    public void handleJoin(ServerPlayerEntity player) {
        Optional<UserRecord> user = userStore.get(player.getUuid());
        if (user.isPresent() && hasValidSession(player, user.get())) {
            authenticate(player, user.get());
            player.sendMessage(Text.literal("Сессия восстановлена.").formatted(Formatting.GREEN), false);
            return;
        }

        lock(player, user.isPresent() ? LoginStage.PASSWORD : LoginStage.REGISTRATION);
        if (user.isPresent()) {
            player.sendMessage(Text.literal("Нужно войти: /login <пароль>").formatted(Formatting.YELLOW), false);
        } else {
            player.sendMessage(Text.literal("Нужно зарегистрироваться: /register <пароль> <пароль>").formatted(Formatting.YELLOW), false);
        }
    }

    public void handleDisconnect(ServerPlayerEntity player) {
        authenticated.remove(player.getUuid());
        pendingLogins.remove(player.getUuid());
    }

    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        pendingTelegramLinks.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < now);

        for (PendingLogin pending : pendingLogins.values()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(pending.playerId);
            if (player == null) {
                continue;
            }
            if (pending.expiresAtMillis < now) {
                player.networkHandler.disconnect(Text.literal("Время авторизации истекло."));
                continue;
            }
            if (player.getWorld().getTime() % 20 == 0) {
                keepLocked(player, pending);
            }
        }
    }

    public AuthMessages.Result register(ServerPlayerEntity player, String password, String repeat) {
        if (userStore.exists(player.getUuid())) {
            return AuthMessages.error("Аккаунт уже зарегистрирован. Используй /login <пароль>.");
        }
        if (!password.equals(repeat)) {
            return AuthMessages.error("Пароли не совпадают.");
        }
        if (password.length() < config.minPasswordLength) {
            return AuthMessages.error("Пароль должен быть не короче " + config.minPasswordLength + " символов.");
        }

        PasswordHasher.HashedPassword hashedPassword = PasswordHasher.hash(password);
        UserRecord record = new UserRecord();
        record.uuid = player.getUuidAsString();
        record.lastKnownName = player.getGameProfile().getName();
        record.passwordSalt = hashedPassword.salt();
        record.passwordHash = hashedPassword.hash();
        record.lastAddress = player.getIp();
        record.lastLoginEpochMillis = Instant.now().toEpochMilli();
        userStore.put(player.getUuid(), record);

        if (config.telegram.enabled && config.telegram.requireForAllUsers) {
            lock(player, LoginStage.TELEGRAM_LINK_REQUIRED);
            return AuthMessages.ok("Аккаунт зарегистрирован. Привяжи Telegram через /linktg, чтобы завершить вход.");
        }

        authenticate(player, record);
        return AuthMessages.ok("Аккаунт зарегистрирован, вход выполнен.");
    }

    public AuthMessages.Result login(ServerPlayerEntity player, String password) {
        AuthMessages.Result throttle = checkThrottle(player.getUuid(), passwordAttempts, config.maxLoginAttempts);
        if (throttle != null) {
            return throttle;
        }
        Optional<UserRecord> user = userStore.get(player.getUuid());
        if (user.isEmpty()) {
            return AuthMessages.error("Аккаунт не зарегистрирован. Используй /register <пароль> <пароль>.");
        }
        UserRecord record = user.get();
        if (!PasswordHasher.verify(password, record.passwordSalt, record.passwordHash)) {
            return failAttempt(player.getUuid(), passwordAttempts, config.maxLoginAttempts, "Неверный пароль.");
        }
        passwordAttempts.remove(player.getUuid());
        if (shouldRequireTelegram(record)) {
            if (record.telegramChatId == null) {
                lock(player, LoginStage.TELEGRAM_LINK_REQUIRED);
                return AuthMessages.error("Требуется Telegram. Используй /linktg и отправь код боту.");
            }
            String code = nextCode();
            String token = nextCallbackToken();
            pendingLogins.put(player.getUuid(), PendingLogin.telegram(player, code, token, ttlMillis(config.telegramCodeTtlSeconds)));
            TelegramBotService bot = TelegramBotService.current();
            if (bot == null || !bot.sendLoginRequest(record.telegramChatId, player.getGameProfile().getName(), player.getIp(), code, token)) {
                return AuthMessages.error("Не удалось отправить Telegram-подтверждение. Попроси администратора проверить настройки бота.");
            }
            return AuthMessages.ok("Пароль принят. Подтверди вход кнопкой в Telegram или введи резервный код: /auth <код>.");
        }

        authenticate(player, record);
        return AuthMessages.ok("Вход выполнен.");
    }

    public AuthMessages.Result verifyTelegramCode(ServerPlayerEntity player, String code) {
        AuthMessages.Result throttle = checkThrottle(player.getUuid(), telegramAttempts, config.maxTelegramCodeAttempts);
        if (throttle != null) {
            return throttle;
        }
        PendingLogin pending = pendingLogins.get(player.getUuid());
        if (pending == null || pending.stage != LoginStage.TELEGRAM_CODE) {
            return AuthMessages.error("Сейчас не ожидается Telegram-код.");
        }
        if (pending.expiresAtMillis < System.currentTimeMillis()) {
            lock(player, LoginStage.PASSWORD);
            return AuthMessages.error("Telegram-код истек. Повтори вход: /login <пароль>.");
        }
        if (!pending.telegramCode.equals(code)) {
            return failAttempt(player.getUuid(), telegramAttempts, config.maxTelegramCodeAttempts, "Неверный Telegram-код.");
        }
        UserRecord record = userStore.get(player.getUuid()).orElse(null);
        if (record == null) {
            return AuthMessages.error("Данные аккаунта не найдены. Зарегистрируйся снова.");
        }
        telegramAttempts.remove(player.getUuid());
        authenticate(player, record);
        return AuthMessages.ok("Telegram подтвержден. Вход выполнен.");
    }

    public TelegramDecisionResult confirmTelegramLogin(String token, boolean approved, MinecraftServer server) {
        for (PendingLogin pending : pendingLogins.values()) {
            if (pending.stage != LoginStage.TELEGRAM_CODE || !pending.telegramCallbackToken.equals(token)) {
                continue;
            }
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(pending.playerId);
            if (player == null) {
                pendingLogins.remove(pending.playerId);
                return TelegramDecisionResult.error("Игрок уже не на сервере.");
            }
            if (pending.expiresAtMillis < System.currentTimeMillis()) {
                lock(player, LoginStage.PASSWORD);
                return TelegramDecisionResult.error("Запрос входа истек.");
            }
            if (!approved) {
                pendingLogins.remove(player.getUuid());
                player.networkHandler.disconnect(Text.literal("Вход отклонен через Telegram."));
                return TelegramDecisionResult.ok("Вход отклонен.");
            }
            UserRecord record = userStore.get(player.getUuid()).orElse(null);
            if (record == null) {
                return TelegramDecisionResult.error("Аккаунт не найден.");
            }
            authenticate(player, record);
            player.sendMessage(Text.literal("Telegram подтвержден. Вход выполнен.").formatted(Formatting.GREEN), false);
            return TelegramDecisionResult.ok("Вход подтвержден.");
        }
        return TelegramDecisionResult.error("Запрос входа не найден или уже обработан.");
    }

    public AuthMessages.Result createTelegramLinkCode(ServerPlayerEntity player) {
        if (!config.telegram.enabled) {
            return AuthMessages.error("Telegram-интеграция отключена на этом сервере.");
        }
        if (!userStore.exists(player.getUuid())) {
            return AuthMessages.error("Сначала зарегистрируйся: /register <пароль> <пароль>.");
        }
        String code = nextLinkCode();
        pendingTelegramLinks.put(code, new PendingTelegramLink(player.getUuid(), ttlMillis(config.telegramCodeTtlSeconds)));
        String deepLink = telegramDeepLink(code);
        if (deepLink.isEmpty()) {
            return AuthMessages.ok("Отправь Telegram-боту: /link " + code);
        }
        return AuthMessages.ok("Открой ссылку для привязки Telegram: " + deepLink + " или отправь боту /link " + code);
    }

    public AuthMessages.Result setTelegram2fa(ServerPlayerEntity player, boolean enabled) {
        if (!isAuthenticated(player)) {
            return AuthMessages.error("Сначала войди в аккаунт, потом меняй настройки 2FA.");
        }
        Optional<UserRecord> user = userStore.get(player.getUuid());
        if (user.isEmpty()) {
            return AuthMessages.error("Сначала зарегистрируйся.");
        }
        UserRecord record = user.get();
        if (record.telegramChatId == null) {
            return AuthMessages.error("Сначала привяжи Telegram через /linktg.");
        }
        record.telegram2faEnabled = enabled;
        userStore.update(record);
        return AuthMessages.ok(enabled ? "Telegram 2FA включена." : "Telegram 2FA выключена.");
    }

    public AuthMessages.Result telegramStatus(ServerPlayerEntity player) {
        Optional<UserRecord> user = userStore.get(player.getUuid());
        if (user.isEmpty()) {
            return AuthMessages.error("Аккаунт не зарегистрирован.");
        }
        UserRecord record = user.get();
        if (!config.telegram.enabled) {
            return AuthMessages.ok("Telegram-интеграция отключена на сервере.");
        }
        if (record.telegramChatId == null) {
            return AuthMessages.ok("Telegram не привязан. Используй /linktg.");
        }
        return AuthMessages.ok(record.telegram2faEnabled ? "Telegram привязан, 2FA включена." : "Telegram привязан, 2FA выключена.");
    }

    public boolean completeTelegramLink(String code, long chatId) {
        PendingTelegramLink pending = pendingTelegramLinks.remove(code);
        if (pending == null || pending.expiresAtMillis < System.currentTimeMillis()) {
            return false;
        }
        Optional<UserRecord> user = userStore.get(pending.playerId);
        if (user.isEmpty()) {
            return false;
        }
        UserRecord record = user.get();
        record.telegramChatId = chatId;
        record.telegram2faEnabled = true;
        userStore.update(record);
        return true;
    }

    public AuthMessages.Result adminInfo(String playerName) {
        Optional<UserRecord> user = userStore.findByName(playerName);
        if (user.isEmpty()) {
            return AuthMessages.error("Игрок не найден в базе Power Auth: " + playerName);
        }
        UserRecord record = user.get();
        String telegram = record.telegramChatId == null ? "не привязан" : (record.telegram2faEnabled ? "привязан, 2FA включена" : "привязан, 2FA выключена");
        return AuthMessages.ok("Игрок: " + record.lastKnownName + " | UUID: " + record.uuid + " | Telegram: " + telegram + " | Последний IP: " + nullToDash(record.lastAddress));
    }

    public AuthMessages.Result adminResetPassword(String playerName) {
        Optional<UserRecord> user = userStore.findByName(playerName);
        if (user.isEmpty()) {
            return AuthMessages.error("Игрок не найден в базе Power Auth: " + playerName);
        }
        UserRecord record = user.get();
        if (!userStore.remove(UUID.fromString(record.uuid))) {
            return AuthMessages.error("Не удалось удалить запись игрока.");
        }
        authenticated.remove(UUID.fromString(record.uuid));
        pendingLogins.remove(UUID.fromString(record.uuid));
        return AuthMessages.ok("Пароль сброшен: " + record.lastKnownName + " должен заново зарегистрироваться.");
    }

    public AuthMessages.Result adminReset2fa(String playerName) {
        Optional<UserRecord> user = userStore.findByName(playerName);
        if (user.isEmpty()) {
            return AuthMessages.error("Игрок не найден в базе Power Auth: " + playerName);
        }
        UserRecord record = user.get();
        record.telegramChatId = null;
        record.telegram2faEnabled = false;
        userStore.update(record);
        return AuthMessages.ok("Telegram 2FA сброшена для " + record.lastKnownName + ".");
    }

    public AuthMessages.Result adminStats() {
        return AuthMessages.ok("Зарегистрировано аккаунтов: " + userStore.count() + " | Ожидают входа: " + pendingLogins.size());
    }

    private void authenticate(ServerPlayerEntity player, UserRecord record) {
        authenticated.add(player.getUuid());
        pendingLogins.remove(player.getUuid());
        passwordAttempts.remove(player.getUuid());
        telegramAttempts.remove(player.getUuid());
        record.lastKnownName = player.getGameProfile().getName();
        record.lastAddress = player.getIp();
        record.lastLoginEpochMillis = Instant.now().toEpochMilli();
        userStore.update(record);
    }

    private void lock(ServerPlayerEntity player, LoginStage stage) {
        pendingLogins.put(player.getUuid(), PendingLogin.locked(player, stage, ttlMillis(config.loginTimeoutSeconds)));
    }

    private void keepLocked(ServerPlayerEntity player, PendingLogin pending) {
        ServerWorld world = player.getServer().getWorld(pending.world);
        if (world == null) {
            return;
        }
        Vec3d pos = pending.position;
        if (!player.getWorld().getRegistryKey().equals(pending.world) || player.getPos().squaredDistanceTo(pos) > 0.02D) {
            player.teleport(world, pos.x, pos.y, pos.z, pending.yaw, pending.pitch);
        }
    }

    private boolean hasValidSession(ServerPlayerEntity player, UserRecord record) {
        long sessionTtlMillis = config.sessionTtlMinutes * 60_000L;
        return record.lastAddress != null
            && record.lastAddress.equals(player.getIp())
            && record.lastLoginEpochMillis + sessionTtlMillis > System.currentTimeMillis();
    }

    private boolean shouldRequireTelegram(UserRecord record) {
        return config.telegram.enabled && (config.telegram.requireForAllUsers || record.telegram2faEnabled);
    }

    private AuthMessages.Result checkThrottle(UUID playerId, Map<UUID, AttemptState> attempts, int maxAttempts) {
        AttemptState state = attempts.get(playerId);
        if (state == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (state.lockedUntilMillis > now) {
            long seconds = Math.max(1L, (state.lockedUntilMillis - now + 999L) / 1000L);
            return AuthMessages.error("Слишком много попыток. Подожди " + seconds + " сек.");
        }
        if (state.windowStartedMillis + config.failedLoginWindowSeconds * 1000L < now) {
            attempts.remove(playerId);
        }
        return null;
    }

    private AuthMessages.Result failAttempt(UUID playerId, Map<UUID, AttemptState> attempts, int maxAttempts, String baseMessage) {
        long now = System.currentTimeMillis();
        AttemptState state = attempts.compute(playerId, (id, existing) -> {
            if (existing == null || existing.windowStartedMillis + config.failedLoginWindowSeconds * 1000L < now) {
                return new AttemptState(1, now, 0L);
            }
            existing.count++;
            if (existing.count >= maxAttempts) {
                existing.lockedUntilMillis = now + config.failedLoginLockSeconds * 1000L;
            }
            return existing;
        });
        if (state.lockedUntilMillis > now) {
            return AuthMessages.error(baseMessage + " Слишком много попыток, блокировка на " + config.failedLoginLockSeconds + " сек.");
        }
        int left = Math.max(0, maxAttempts - state.count);
        return AuthMessages.error(baseMessage + " Осталось попыток: " + left + ".");
    }

    private String telegramDeepLink(String code) {
        if (config.telegram.botUsername == null || config.telegram.botUsername.isBlank()) {
            return "";
        }
        return "https://t.me/" + config.telegram.botUsername.replace("@", "") + "?start=" + code;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static long ttlMillis(int seconds) {
        return System.currentTimeMillis() + seconds * 1000L;
    }

    private static String nextCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    private static String nextCallbackToken() {
        return Long.toString(Math.abs(RANDOM.nextLong()), 36) + Long.toString(System.nanoTime(), 36);
    }

    private static String nextLinkCode() {
        return Integer.toString(100_000_000 + RANDOM.nextInt(900_000_000));
    }

    private enum LoginStage {
        REGISTRATION,
        PASSWORD,
        TELEGRAM_CODE,
        TELEGRAM_LINK_REQUIRED
    }

    public record TelegramDecisionResult(boolean success, String message) {
        static TelegramDecisionResult ok(String message) {
            return new TelegramDecisionResult(true, message);
        }

        static TelegramDecisionResult error(String message) {
            return new TelegramDecisionResult(false, message);
        }
    }

    private record PendingLogin(UUID playerId, LoginStage stage, String telegramCode, String telegramCallbackToken, long expiresAtMillis,
                                net.minecraft.registry.RegistryKey<World> world, Vec3d position, float yaw, float pitch) {
        static PendingLogin locked(ServerPlayerEntity player, LoginStage stage, long expiresAtMillis) {
            return new PendingLogin(player.getUuid(), stage, "", "", expiresAtMillis, player.getWorld().getRegistryKey(),
                player.getPos(), player.getYaw(), player.getPitch());
        }

        static PendingLogin telegram(ServerPlayerEntity player, String code, String token, long expiresAtMillis) {
            return new PendingLogin(player.getUuid(), LoginStage.TELEGRAM_CODE, code, token, expiresAtMillis,
                player.getWorld().getRegistryKey(), player.getPos(), player.getYaw(), player.getPitch());
        }
    }

    private record PendingTelegramLink(UUID playerId, long expiresAtMillis) {
    }

    private static final class AttemptState {
        private int count;
        private long windowStartedMillis;
        private long lockedUntilMillis;

        private AttemptState(int count, long windowStartedMillis, long lockedUntilMillis) {
            this.count = count;
            this.windowStartedMillis = windowStartedMillis;
            this.lockedUntilMillis = lockedUntilMillis;
        }
    }
}
