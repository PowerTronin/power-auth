package com.powerauth.auth;

public final class UserRecord {
    public String uuid;
    public String lastKnownName;
    public String passwordHash;
    public String passwordSalt;
    public Long telegramChatId;
    public boolean telegram2faEnabled;
    public String lastAddress;
    public long lastLoginEpochMillis;
}
