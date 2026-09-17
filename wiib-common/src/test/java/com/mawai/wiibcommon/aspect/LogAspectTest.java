package com.mawai.wiibcommon.aspect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志切面参数脱敏回归。Arrays.toString 会把 record/POJO 的 toString 全量吐出来，
 * 曾把 BYOK 明文 key（/api/ai/trader 三端点）、登录密码与邀请码（/api/auth 两端点）、
 * 平台自有 key（/api/ai/admin/keys）原样打进日志——按字段名识别打码，新接口天然被覆盖。
 */
class LogAspectTest {

    @Test
    void record风格_apiKey打码_其余字段保留() {
        String s = LogAspect.maskSensitive(
                "[UpsertReq[name=小虎, baseUrl=https://api.x.ai, apiKey=sk-live-abc123, intervalCode=5m]]");
        assertThat(s).doesNotContain("sk-live-abc123")
                .contains("apiKey=***")
                .contains("name=小虎")
                .contains("baseUrl=https://api.x.ai");
    }

    @Test
    void lombok风格_密码与邀请码打码() {
        String s = LogAspect.maskSensitive(
                "[RegisterRequest(username=bob, password=hunter2, inviteCode=WIIB-2026)]");
        assertThat(s).doesNotContain("hunter2").doesNotContain("WIIB-2026")
                .contains("password=***")
                .contains("inviteCode=***")
                .contains("username=bob");
    }

    @Test
    void json风格参数同样打码() {
        String s = LogAspect.maskSensitive(
                "[{\"provider\":\"xai\",\"apiKey\":\"sk-admin-xyz\",\"baseUrl\":\"https://api.x.ai\"}]");
        assertThat(s).doesNotContain("sk-admin-xyz")
                .contains("\"apiKey\":\"***")
                .contains("xai");
    }

    @Test
    void 包含式命名变体一并覆盖() {
        String s = LogAspect.maskSensitive(
                "[Req[newPassword=a1, xaiApiKey=sk-2, api_key=sk-3, accessToken=t4, clientSecret=s5]]");
        assertThat(s).doesNotContain("a1").doesNotContain("sk-2").doesNotContain("sk-3")
                .doesNotContain("t4").doesNotContain("s5");
    }

    @Test
    void 无敏感字段原样通过() {
        String plain = "[BTCUSDT, 730, 100000]";
        assertThat(LogAspect.maskSensitive(plain)).isEqualTo(plain);
        assertThat(LogAspect.maskSensitive(null)).isNull();
        assertThat(LogAspect.maskSensitive("")).isEmpty();
    }
}
