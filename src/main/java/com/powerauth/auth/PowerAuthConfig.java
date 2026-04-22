package com.powerauth.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.powerauth.PowerAuthMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PowerAuthConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int minPasswordLength = 6;
    public int sessionTtlMinutes = 30;
    public int loginTimeoutSeconds = 90;
    public int telegramCodeTtlSeconds = 120;
    public int maxLoginAttempts = 5;
    public int failedLoginWindowSeconds = 120;
    public int failedLoginLockSeconds = 60;
    public int maxTelegramCodeAttempts = 5;
    public int adminPermissionLevel = 3;
    public List<String> allowedCommandsWhileLocked = List.of("login", "register", "auth", "linktg");
    public Telegram telegram = new Telegram();

    public static PowerAuthConfig load(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.notExists(path)) {
                PowerAuthConfig config = new PowerAuthConfig();
                Files.writeString(path, GSON.toJson(config));
                return config;
            }
            return GSON.fromJson(Files.readString(path), PowerAuthConfig.class);
        } catch (IOException e) {
            PowerAuthMod.LOGGER.error("Failed to load Power Auth config. Falling back to defaults.", e);
            return new PowerAuthConfig();
        }
    }

    public static final class Telegram {
        public boolean enabled = false;
        public boolean requireForAllUsers = false;
        public String botToken = "";
        public int pollTimeoutSeconds = 25;
        public int reconnectDelayMillis = 2500;
        public String botUsername = "";
        public String loginCodeMessage = "Код входа в Minecraft: %s. Он скоро истечет.";
        public String loginRequestMessage = "Кто-то пытается войти в ваш Minecraft-аккаунт %s с IP %s. Если это вы, нажмите Подтвердить. Резервный код: %s";
        public String startMessage = "Power Auth: чтобы привязать Telegram, зайди на сервер Minecraft и введи /linktg. Затем отправь сюда команду /link CODE, которую покажет сервер. После привязки при входе вводи пароль в Minecraft и подтверждай вход кнопкой в этом боте. Если кнопка недоступна, используй код из сообщения командой /auth CODE в игре.";
    }
}
