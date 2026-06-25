package com.mawai.wiibquant.agent.binance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Binance SIGNED 端点签名工具：对待签字符串做 HMAC-SHA256，输出小写 hex。
 * 待签内容 = query string + request body（本项目所有参数都走 query string，故 body 为空）。
 */
public class BinanceSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] secretKeyBytes;

    public BinanceSigner(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("Binance secretKey 不能为空");
        }
        this.secretKeyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_SHA256));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Binance HMAC-SHA256 签名失败", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
