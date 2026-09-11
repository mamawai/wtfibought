package com.mawai.wiibagent.chat.store;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;

/**
 * 后端自己写进历史的两种特殊行的码。前端按码分流：补答行不给重新生成、续跑指令还原成过程轨。
 * <p>
 * <b>判定为什么逐语言比对</b>：这两行的正文是词表文案，跟着用户语言变。认死一门语言的字面量，
 * 换语言就静默失配——补答行冒出"重新生成"按钮、历史里多出一句用户从没说过的话。
 * 遍历 {@link AgentLang} 全比一遍，同一个会话中途切语言也认得回来。
 * <p>
 * <b>码语言无关</b>：判定只在这里做一次，下发给前端的是码，前端不再碰文案。
 */
public final class ChatRowKind {

    /** 补答行（assistant）：对应的提问不在会话末尾，后端回退会误伤中间轮次，所以没有重新生成 */
    public static final String DEFERRED = "deferred";
    /** HITL 批准后自动补发的续跑指令（user）：属于"批准"这个动作，不是用户打的字 */
    public static final String HITL_RESUME = "hitlResume";

    private ChatRowKind() {
    }

    /** 认出这一行是哪种；普通行返回 null */
    public static String of(String role, String content, PromptCatalog prompts) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        if ("user".equals(role)) {
            return anyLang(content, prompts, "chat.hitl.resumeMessage", true) ? HITL_RESUME : null;
        }
        return anyLang(content, prompts, "chat.deferred.prefix", false) ? DEFERRED : null;
    }

    /** 续跑指令是整句照发，全等比；补答标头后面接着问题摘要，只能前缀比 */
    private static boolean anyLang(String content, PromptCatalog prompts, String key, boolean exact) {
        for (AgentLang lang : AgentLang.values()) {
            String text = prompts.get(lang, key);
            if (exact ? content.equals(text) : content.startsWith(text)) {
                return true;
            }
        }
        return false;
    }
}
