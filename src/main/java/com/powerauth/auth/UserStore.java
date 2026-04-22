package com.powerauth.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.powerauth.PowerAuthMod;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class UserStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, UserRecord>>() {
    }.getType();

    private final Path path;
    private final Map<String, UserRecord> users;

    public UserStore(Path path) {
        this.path = path;
        this.users = load();
    }

    public synchronized Optional<UserRecord> get(UUID uuid) {
        return Optional.ofNullable(users.get(uuid.toString()));
    }

    public synchronized boolean exists(UUID uuid) {
        return users.containsKey(uuid.toString());
    }

    public synchronized Optional<UserRecord> findByName(String name) {
        return users.values().stream()
            .filter(user -> user.lastKnownName != null && user.lastKnownName.equalsIgnoreCase(name))
            .findFirst();
    }

    public synchronized int count() {
        return users.size();
    }

    public synchronized void put(UUID uuid, UserRecord record) {
        users.put(uuid.toString(), record);
        save();
    }

    public synchronized void update(UserRecord record) {
        users.put(record.uuid, record);
        save();
    }

    public synchronized boolean remove(UUID uuid) {
        boolean removed = users.remove(uuid.toString()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    private Map<String, UserRecord> load() {
        try {
            if (Files.notExists(path)) {
                return new HashMap<>();
            }
            Map<String, UserRecord> loaded = GSON.fromJson(Files.readString(path), MAP_TYPE);
            return loaded == null ? new HashMap<>() : new HashMap<>(loaded);
        } catch (IOException e) {
            PowerAuthMod.LOGGER.error("Failed to load users from {}", path, e);
            return new HashMap<>();
        }
    }

    private void save() {
        try {
            Files.writeString(path, GSON.toJson(users));
        } catch (IOException e) {
            PowerAuthMod.LOGGER.error("Failed to save users to {}", path, e);
        }
    }
}
