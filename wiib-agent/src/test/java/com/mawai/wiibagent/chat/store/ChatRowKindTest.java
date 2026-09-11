package com.mawai.wiibagent.chat.store;

import com.mawai.wiibagent.chat.ChatTestEndpoints;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 特殊行的认领。要钉的是"<b>换了语言还认得出</b>"——这两行的正文是词表文案，
 * 从前只认中文那一份，英文用户身上就会现出两个症状：补答行冒出重新生成按钮、
 * 历史里多出一句自己从没说过的话。
 */
class ChatRowKindTest {

    private static String prefix(AgentLang lang) {
        return ChatTestEndpoints.PROMPTS.get(lang, "chat.deferred.prefix");
    }

    private static String resume(AgentLang lang) {
        return ChatTestEndpoints.PROMPTS.get(lang, "chat.hitl.resumeMessage");
    }

    @Test
    void 两门语言的补答行都认得出() {
        for (AgentLang lang : AgentLang.values()) {
            assertThat(ChatRowKind.of("assistant", prefix(lang) + "问题」】\n\n答案", ChatTestEndpoints.PROMPTS))
                    .as("%s 的补答行", lang.code())
                    .isEqualTo(ChatRowKind.DEFERRED);
        }
    }

    @Test
    void 两门语言的续跑指令都认得出() {
        for (AgentLang lang : AgentLang.values()) {
            assertThat(ChatRowKind.of("user", resume(lang), ChatTestEndpoints.PROMPTS))
                    .as("%s 的续跑指令", lang.code())
                    .isEqualTo(ChatRowKind.HITL_RESUME);
        }
    }

    @Test
    void 普通问答不认领() {
        assertThat(ChatRowKind.of("user", "BTC 怎么样", ChatTestEndpoints.PROMPTS)).isNull();
        assertThat(ChatRowKind.of("assistant", "BTC 现在……", ChatTestEndpoints.PROMPTS)).isNull();
        assertThat(ChatRowKind.of("assistant", null, ChatTestEndpoints.PROMPTS)).isNull();
    }

    @Test
    void 两种码按角色分流不互串() {
        // 用户自己把补答标头当问题打进来，那也只是一句提问，不是补答行
        assertThat(ChatRowKind.of("user", prefix(AgentLang.ZH) + "问题」】", ChatTestEndpoints.PROMPTS)).isNull();
        // 反过来，模型答案里复述了那句续跑指令也不算续跑指令
        assertThat(ChatRowKind.of("assistant", resume(AgentLang.ZH), ChatTestEndpoints.PROMPTS)).isNull();
    }
}
