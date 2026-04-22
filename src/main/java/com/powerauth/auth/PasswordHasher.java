package com.powerauth.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;

    private PasswordHasher() {
    }

    public static HashedPassword hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt);
        return new HashedPassword(Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash));
    }

    public static boolean verify(String password, String saltBase64, String hashBase64) {
        byte[] salt = Base64.getDecoder().decode(saltBase64);
        byte[] expected = Base64.getDecoder().decode(hashBase64);
        byte[] actual = pbkdf2(password, salt);
        return constantTimeEquals(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is unavailable", e);
        }
    }

    private static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length; i++) {
            diff |= expected[i] ^ actual[i];
        }
        return diff == 0;
    }

    public record HashedPassword(String salt, String hash) {
    }
}
