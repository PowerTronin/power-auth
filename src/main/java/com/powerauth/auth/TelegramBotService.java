package com.powerauth.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.powerauth.PowerAuthMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TelegramBotService implements Runnable {
    private static volatile TelegramBotService current;

    private final PowerAuthConfig.Telegram config;
    private final AuthManager authManager;
    private final MinecraftServer server;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private long offset = 0;

    public TelegramBotService(PowerAuthConfig.Telegram config, AuthManager authManager, MinecraftServer server) {
        this.config = config;
        this.authManager = authManager;
        this.server = server;
    }

    public static TelegramBotService current() {
        return current;
    }

    public void start() {
        if (config.botToken == null || config.botToken.isBlank()) {
            PowerAuthMod.LOGGER.warn("Telegram is enabled, but botToken is empty.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        current = this;
        thread = new Thread(this, "PowerAuth-TelegramBot");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (current == this) {
            current = null;
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                poll();
            } catch (Exception e) {
                PowerAuthMod.LOGGER.warn("Telegram polling failed.", e);
                sleep(config.reconnectDelayMillis);
            }
        }
    }

    public boolean sendLoginRequest(long chatId, String playerName, String ip, String code, String token) {
        String text = String.format(config.loginRequestMessage, playerName, ip, code);
        JsonObject replyMarkup = inlineLoginKeyboard(token);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("chat_id", chatId);
            body.addProperty("text", text);
            body.add("reply_markup", replyMarkup);
            JsonObject result = post("sendMessage", body);
            return result.get("ok").getAsBoolean();
        } catch (Exception e) {
            PowerAuthMod.LOGGER.warn("Failed to send Telegram login request.", e);
            return sendLoginCode(chatId, code);
        }
    }

    private boolean sendLoginCode(long chatId, String code) {
        String text = String.format(config.loginCodeMessage, code);
        try {
            JsonObject body = new JsonObject();
            body.addProperty("chat_id", chatId);
            body.addProperty("text", text);
            JsonObject result = post("sendMessage", body);
            return result.get("ok").getAsBoolean();
        } catch (Exception e) {
            PowerAuthMod.LOGGER.warn("Failed to send fallback Telegram login code.", e);
            return false;
        }
    }

    private void poll() throws IOException, InterruptedException {
        JsonObject response = call("getUpdates?timeout=" + config.pollTimeoutSeconds + "&offset=" + offset);
        if (!response.get("ok").getAsBoolean()) {
            sleep(config.reconnectDelayMillis);
            return;
        }
        JsonArray updates = response.getAsJsonArray("result");
        for (int i = 0; i < updates.size(); i++) {
            JsonObject update = updates.get(i).getAsJsonObject();
            offset = Math.max(offset, update.get("update_id").getAsLong() + 1);
            handleUpdate(update);
        }
    }

    private void handleUpdate(JsonObject update) {
        if (update.has("callback_query")) {
            handleCallbackQuery(update.getAsJsonObject("callback_query"));
            return;
        }
        if (!update.has("message")) {
            return;
        }
        JsonObject message = update.getAsJsonObject("message");
        if (!message.has("text") || !message.has("chat")) {
            return;
        }
        long chatId = message.getAsJsonObject("chat").get("id").getAsLong();
        String text = message.get("text").getAsString().trim();
        if (text.equals("/start") || text.equals("/help")) {
            sendPlain(chatId, config.startMessage);
            return;
        }
        if (text.startsWith("/start ")) {
            text = "/link " + text.substring("/start ".length()).trim();
        }
        if (!text.startsWith("/link ")) {
            sendPlain(chatId, config.startMessage);
            return;
        }
        String code = text.substring("/link ".length()).trim();
        if (authManager.completeTelegramLink(code, chatId)) {
            sendPlain(chatId, "Telegram привязан. 2FA включена.");
        } else {
            sendPlain(chatId, "Код привязки неверный или истек.");
        }
    }

    private void handleCallbackQuery(JsonObject callbackQuery) {
        String callbackId = callbackQuery.get("id").getAsString();
        if (!callbackQuery.has("data")) {
            answerCallback(callbackId, "Некорректная кнопка.");
            return;
        }
        String data = callbackQuery.get("data").getAsString();
        boolean approved;
        String token;
        if (data.startsWith("approve:")) {
            approved = true;
            token = data.substring("approve:".length());
        } else if (data.startsWith("deny:")) {
            approved = false;
            token = data.substring("deny:".length());
        } else {
            answerCallback(callbackId, "Некорректная кнопка.");
            return;
        }

        server.execute(() -> {
            AuthManager.TelegramDecisionResult result = authManager.confirmTelegramLogin(token, approved, server);
            answerCallback(callbackId, result.message());
            if (callbackQuery.has("message")) {
                editCallbackMessage(callbackQuery.getAsJsonObject("message"), result.message());
            }
        });
    }

    private JsonObject call(String methodAndQuery) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot" + config.botToken + "/" + methodAndQuery))
            .timeout(Duration.ofSeconds(config.pollTimeoutSeconds + 10L))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject post(String method, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot" + config.botToken + "/" + method))
            .timeout(Duration.ofSeconds(config.pollTimeoutSeconds + 10L))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void sendPlain(long chatId, String text) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("chat_id", chatId);
            body.addProperty("text", text);
            post("sendMessage", body);
        } catch (Exception e) {
            PowerAuthMod.LOGGER.warn("Failed to send Telegram message to {}", chatId, e);
        }
    }

    private void answerCallback(String callbackId, String text) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("callback_query_id", callbackId);
            body.addProperty("text", text);
            body.addProperty("show_alert", false);
            post("answerCallbackQuery", body);
        } catch (Exception e) {
            PowerAuthMod.LOGGER.warn("Failed to answer Telegram callback.", e);
        }
    }

    private void editCallbackMessage(JsonObject message, String text) {
        if (!message.has("chat") || !message.has("message_id")) {
            return;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("chat_id", message.getAsJsonObject("chat").get("id").getAsLong());
            body.addProperty("message_id", message.get("message_id").getAsInt());
            body.addProperty("text", text);
            post("editMessageText", body);
        } catch (Exception e) {
            PowerAuthMod.LOGGER.warn("Failed to edit Telegram callback message.", e);
        }
    }

    private static JsonObject inlineLoginKeyboard(String token) {
        JsonObject keyboard = new JsonObject();
        JsonArray rows = new JsonArray();
        JsonArray row = new JsonArray();
        row.add(button("Подтвердить", "approve:" + token));
        row.add(button("Отклонить", "deny:" + token));
        rows.add(row);
        keyboard.add("inline_keyboard", rows);
        return keyboard;
    }

    private static JsonObject button(String text, String callbackData) {
        JsonObject button = new JsonObject();
        button.addProperty("text", text);
        button.addProperty("callback_data", callbackData);
        return button;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
