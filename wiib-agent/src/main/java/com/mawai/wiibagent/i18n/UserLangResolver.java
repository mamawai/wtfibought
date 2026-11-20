package com.mawai.wiibagent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimInternalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 查用户的 AI 产出语言。lang 列在 sim 的 user 表，agent 不直连它的库表，走 internal API
 * （与 behavior 取数同一条通道）。
 * <p>不加缓存：localhost 一跳，相对一次 LLM 调用可以忽略；缓存换来的是"刚切完语言还出旧语言"。
 */
@Component
@RequiredArgsConstructor
public class UserLangResolver {

    private final SimInternalClient simClient;

    /** 取不到（sim 挂了 / 列是 NULL / 值认不出）一律 ZH——回落中文即维持存量行为 */
    public AgentLang of(long userId) {
        return AgentLang.of(simClient.getJson("/internal/user/" + userId + "/lang"));
    }
}
