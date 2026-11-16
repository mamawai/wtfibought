package com.mawai.wiibagent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.LangBundle;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 提示词词表：{@code resources/prompts/<语言码>/<域>.yml}，装配规则、缺 key 回落、占位符校验
 * 全在 {@link LangBundle}（与界面文案词表 {@code messages/} 共用同一套引擎）。
 * <p>
 * <b>它与界面文案的分界</b>：这里装的是<b>喂给模型</b>的东西——系统提示词、工具描述，以及
 * AI 自己产出后落库、跟着 trader 主人语言走的那些话（决策 error/reasoning、paused_reason）。
 * 给用户看的报错与提示走 {@code MessageCatalog}，语言跟当次请求的界面语言，两者语言来源不同。
 * <p>
 * <b>key 约定</b>：{@code <域>.<用途>}（behavior.system / trader.systemTemplate），
 * 工具描述固定 {@code tool.<工具名>}。域前缀写在 yml 内部，分了文件也不去掉。
 */
@Component
public class PromptCatalog {

    private final LangBundle bundle;

    public PromptCatalog() {
        // classpath*: 与 mybatis mapper-locations 同款——打成 Boot fat jar 后照样枚举得到目录里的条目
        this.bundle = new LangBundle("提示词", "classpath*:prompts/*/*.yml");
    }

    /** 取一条提示词。缺 key 只会回落或抛，不会给空串 */
    public String get(AgentLang lang, String key) {
        return bundle.get(lang, key);
    }

    /** 取一条提示词并填 {{占位符}}。少给一个值就抛——留着 {{xxx}} 发给模型是纯事故 */
    public String get(AgentLang lang, String key, Map<String, Object> vars) {
        return bundle.get(lang, key, vars);
    }

    /** 取原文，不填占位符；取不到返回 null，不抛。提示词正文一律走 {@link #get} */
    public String find(AgentLang lang, String key) {
        return bundle.find(lang, key);
    }
}
