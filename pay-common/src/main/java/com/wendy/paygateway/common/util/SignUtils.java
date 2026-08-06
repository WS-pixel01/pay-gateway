package com.wendy.paygateway.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Channel signature helper (WeChat-style MD5 signing).
 *
 * <p>Rule: sort parameters by key ascending, drop blank values and the sign fields, join as
 * {@code a=1&amp;b=2}, append {@code &amp;key=<merchant secret>}, MD5 it and upper-case the result.
 */
public final class SignUtils {

    public static final String SIGN_FIELD = "sign";

    private SignUtils() {
    }

    /** Build the string to be signed. */
    public static String buildSignContent(Map<String, String> params, String apiKey) {
        TreeMap<String, String> sorted = new TreeMap<>();
        params.forEach((k, v) -> {
            if (k != null && v != null && !v.isEmpty() && !SIGN_FIELD.equals(k) && !"sign_type".equals(k)) {
                sorted.put(k, v);
            }
        });
        StringBuilder sb = new StringBuilder();
        sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
        sb.append("key=").append(apiKey == null ? "" : apiKey);
        return sb.toString();
    }

    /** MD5 signature. */
    public static String md5Sign(Map<String, String> params, String apiKey) {
        return md5(buildSignContent(params, apiKey));
    }

    /** Verify a signature using a constant-time comparison to avoid timing side channels. */
    public static boolean verifyMd5(Map<String, String> params, String apiKey) {
        String sign = params.get(SIGN_FIELD);
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        return constantTimeEquals(sign, md5Sign(params, apiKey));
    }

    public static String md5(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 computation failed", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
