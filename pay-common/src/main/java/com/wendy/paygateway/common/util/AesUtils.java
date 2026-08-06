package com.wendy.paygateway.common.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM payload encryption, mirroring the encrypted {@code resource} field of WeChat Pay v3.
 *
 * <p>Ciphertext layout: Base64(iv(12 bytes) || cipherText || tag). The key is a Base64-encoded
 * 16- or 32-byte value.
 */
public final class AesUtils {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BIT_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesUtils() {
    }

    public static String encrypt(String plainText, String base64Key) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyOf(base64Key), new GCMParameterSpec(TAG_BIT_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES encryption failed", e);
        }
    }

    public static String decrypt(String base64CipherText, String base64Key) {
        try {
            byte[] all = Base64.getDecoder().decode(base64CipherText);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            byte[] cipherText = new byte[all.length - IV_LENGTH];
            System.arraycopy(all, IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keyOf(base64Key), new GCMParameterSpec(TAG_BIT_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES decryption failed", e);
        }
    }

    /** Generate a Base64-encoded 256-bit key. */
    public static String generateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SecretKeySpec keyOf(String base64Key) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }
}
