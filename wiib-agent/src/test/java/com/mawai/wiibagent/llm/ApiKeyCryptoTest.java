package com.mawai.wiibagent.llm;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyCryptoTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundtrip() {
        ApiKeyCrypto c = new ApiKeyCrypto(SECRET);

        String enc = c.encrypt("sk-test-123");

        assertThat(enc).isNotEqualTo("sk-test-123");
        assertThat(c.decrypt(enc)).isEqualTo("sk-test-123");
    }

    @Test
    void sameplaintextEncryptsDifferently() {
        ApiKeyCrypto c = new ApiKeyCrypto(SECRET);

        // 随机IV：同明文两次加密密文不同，防彩虹比对
        assertThat(c.encrypt("sk-test-123")).isNotEqualTo(c.encrypt("sk-test-123"));
    }

    @Test
    void missingSecretFailsLoudly() {
        ApiKeyCrypto c = new ApiKeyCrypto("");

        // 未配置密钥不挡进程启动，但用到时必须响亮失败而不是静默存明文
        assertThatThrownBy(() -> c.encrypt("sk-x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt("xxx")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextRejected() {
        ApiKeyCrypto c = new ApiKeyCrypto(SECRET);
        String enc = c.encrypt("sk-test-123");
        byte[] raw = Base64.getDecoder().decode(enc);
        raw[raw.length - 1] ^= 1; // 篡改末字节，GCM tag 校验必须拒绝

        assertThatThrownBy(() -> c.decrypt(Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class);
    }
}
