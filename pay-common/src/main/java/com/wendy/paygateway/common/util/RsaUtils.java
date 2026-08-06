package com.wendy.paygateway.common.util;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/** RSA2 (SHA256withRSA) signing helper, mirroring the Alipay Open Platform verification flow. */
public final class RsaUtils {

    private static final String ALGORITHM = "RSA";
    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;

    private RsaUtils() {
    }

    /** Generate a Base64-encoded key pair, used to bootstrap the sandbox channel on startup. */
    public static Rsa2KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(KEY_SIZE);
            KeyPair keyPair = generator.generateKeyPair();
            return new Rsa2KeyPair(
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    /** Sign the sorted parameter string. */
    public static String sign(Map<String, String> params, String privateKeyBase64) {
        return sign(buildContent(params), privateKeyBase64);
    }

    public static String sign(String content, String privateKeyBase64) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64)));
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("RSA signing failed", e);
        }
    }

    public static boolean verify(Map<String, String> params, String signBase64, String publicKeyBase64) {
        return verify(buildContent(params), signBase64, publicKeyBase64);
    }

    public static boolean verify(String content, String signBase64, String publicKeyBase64) {
        try {
            PublicKey publicKey = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signBase64));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Build the string to be signed: key-sorted, blank values and sign/sign_type dropped, and no
     * merchant secret appended (RSA does not need one).
     */
    public static String buildContent(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        new java.util.TreeMap<>(params).forEach((k, v) -> {
            if (v != null && !v.isEmpty() && !SignUtils.SIGN_FIELD.equals(k) && !"sign_type".equals(k)) {
                sb.append(k).append('=').append(v).append('&');
            }
        });
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /** Base64-encoded RSA key pair. */
    @Getter
    public static class Rsa2KeyPair {
        private final String privateKey;
        private final String publicKey;

        public Rsa2KeyPair(String privateKey, String publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }
}
