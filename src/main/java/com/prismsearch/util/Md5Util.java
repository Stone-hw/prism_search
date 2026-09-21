package com.prismsearch.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5 helper used to derive fixed-length cache keys.
 */
public final class Md5Util {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Md5Util() {
    }

    public static String md5Hex(String input) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed by the JCA spec; treat as fatal.
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
