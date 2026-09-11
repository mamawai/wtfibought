package com.mawai.wiibagent.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户 API key 加密器：AES-256-GCM，密文格式 base64(iv12B + cipher+tag)。
 * 密钥来自环境变量 WIIB_TRADER_KEY_SECRET（base64 的 32 字节）；未配置不挡进程启动，
 * 但加解密时响亮失败——绝不静默存明文。密钥/明文任何情况下不进日志。
 * <p>
 * 名字里的 TRADER 是历史包袱（现同时服务 ai_trader 和 user_llm_config），保留为了不动生产环境变量。
 */
@Component
public class ApiKeyCrypto {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public ApiKeyCrypto(@Value("${WIIB_TRADER_KEY_SECRET:}") String secretBase64) {
        SecretKeySpec parsed = null;
        if (secretBase64 != null && !secretBase64.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(secretBase64.trim());
            if (raw.length != 32) {
                throw new IllegalStateException("WIIB_TRADER_KEY_SECRET 必须是 base64 的 32 字节");
            }
            parsed = new SecretKeySpec(raw, "AES");
        }
        this.key = parsed;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + enc.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(enc, 0, out, IV_LEN, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API key 加密失败", e);
        }
    }

    public String decrypt(String encBase64) {
        requireKey();
        try {
            byte[] raw = Base64.getDecoder().decode(encBase64);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN));
            byte[] plain = cipher.doFinal(raw, IV_LEN, raw.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 含 GCM tag 校验失败（密文被篡改/密钥不对）；不透出密文细节
            throw new IllegalStateException("API key 解密失败", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("未配置 WIIB_TRADER_KEY_SECRET，无法加解密用户 API key");
        }
    }
}
