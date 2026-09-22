package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserJevConfig;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ApiKeyCrypto;
import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevClient.Question;
import com.mawai.wiibagent.llm.jev.JevConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 路由的 Jev 层：用户配了 Jev 就先问它"这个问题需要哪些专家"，三道英文是非题一次问完，
 * 概率过线的进名单。Jev 答的是提问本身的分类，不看专家已经取回什么——去重与轮次上限在
 * {@link ChatTurnRunner} 的循环里，第二轮再问答案一样，名单被去重吃空就转汇总。
 * <p>
 * 空 = Jev 没参与（未配置 / 调用失败 / 回包缺题），调用方走原来的轻模型路由。
 * 三题都不过线是 Jev 的正常判断（闲聊、行为分析这类不需要专家），返回 FINISH 不算失败。
 * <p>
 * state 只放本轮提问与最近几条用户/助手原文。专家回填的大段数据、系统收尾指令、摘要、工具回执
 * 都不进：Jev 官方明说无关内容多了准确率掉。用户的中文原样给，题目与 criteria 一律英文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevRouter {

    /** 概率过这条线就派。先按多数信念定 0.5，三个概率都打日志，跑一阵再调 */
    static final double THRESHOLD = 0.5;
    /** 本轮提问之前再带几条用户/助手原文，够解开"那它现在呢"这类指代 */
    static final int RECENT_MESSAGES = 4;
    /** 单条进 state 的长度上限 */
    static final int TEXT_LIMIT = 400;

    /** 题号 → 专家名，顺序固定 */
    static final Map<String, String> EXPERT_BY_QUESTION = new LinkedHashMap<>();

    static final Map<String, Question> QUESTIONS = new LinkedHashMap<>();

    static {
        EXPERT_BY_QUESTION.put("need_market", ChatAgentFactory.MARKET_AGENT);
        EXPERT_BY_QUESTION.put("need_news", ChatAgentFactory.NEWS_AGENT);
        EXPERT_BY_QUESTION.put("need_trader", ChatAgentFactory.TRADER_AGENT);

        QUESTIONS.put("need_market", Question.noul(
                "Answering `latest_question` well requires fetching live crypto market data now: current prices, "
                        + "candles or indicators, market structure, open interest, liquidations, funding rates, "
                        + "options or the order book.",
                "The question asks about, or depends on, the current state of a crypto market.",
                "The question can be answered without any live market numbers."));
        QUESTIONS.put("need_news", Question.noul(
                "Answering `latest_question` well requires recent crypto news flashes or market events.",
                "The question asks what happened, why the market moved, or about news, events or announcements.",
                "The question does not depend on recent news."));
        QUESTIONS.put("need_trader", Question.noul(
                "`latest_question` is about the user's own AI trader: its status, positions, trades, "
                        + "trade plans, reviews or learning notes.",
                "The user asks about their own AI trader (\"my trader\", what it holds, why it traded, its plan or review).",
                "The question is not about the user's own AI trader."));
    }

    /** 专家回填的消息以出处标注开头，中英两套括号都认 */
    private static final Pattern EXPERT_TAG = Pattern.compile("^\\s*[\\[【](market_agent|news_agent|trader_agent)\\b");

    private final JevConfigService configService;
    private final JevClient client;
    private final ApiKeyCrypto crypto;
    private final PromptCatalog prompts;

    /** 空=Jev 没参与，走旧路由；非空=Jev 的名单，可能只有 FINISH */
    public Optional<List<String>> route(long userId, List<Message> working) {
        UserJevConfig cfg = configService.of(userId);
        if (cfg == null) {
            return Optional.empty();
        }
        Map<String, Object> state = state(working);
        try {
            JevClient.Response r = client.ask(cfg.getBaseUrl(), crypto.decrypt(cfg.getApiKeyEnc()), cfg.getModel(),
                    state, QUESTIONS);
            List<String> next = decide(r);
            log.info("[JevRoute] userId={} next={} probabilities={}", userId, next, probabilities(r));
            return Optional.of(next);
        } catch (Exception e) {
            log.warn("[JevRoute] 调用失败，回落轻模型路由 userId={} msg={}", userId, e.toString());
            return Optional.empty();
        }
    }

    /** 三题概率 → 名单（顺序固定）；缺题当失败抛出；一个都不过线 → FINISH */
    static List<String> decide(JevClient.Response r) {
        List<String> next = new ArrayList<>();
        for (Map.Entry<String, String> e : EXPERT_BY_QUESTION.entrySet()) {
            JevClient.Answer a = r.answers().get(e.getKey());
            if (a == null || a.noul() == null) {
                throw new IllegalStateException("Jev 回包缺题 " + e.getKey());
            }
            if (a.noul() >= THRESHOLD) {
                next.add(e.getValue());
            }
        }
        return next.isEmpty() ? List.of(ChatTurnRunner.FINISH) : next;
    }

    private static Map<String, Double> probabilities(JevClient.Response r) {
        Map<String, Double> out = new LinkedHashMap<>();
        r.answers().forEach((k, a) -> out.put(k, a.noul()));
        return out;
    }

    /**
     * state：{@code latest_question} 本轮提问（剥掉时间行与问题前缀）+ {@code recent_conversation}
     * 之前最近几条用户/助手原文（时间顺序）。包私有供单测看形状。
     */
    Map<String, Object> state(List<Message> working) {
        int latestAt = working.size() - 1;
        while (latestAt >= 0 && !(working.get(latestAt) instanceof UserMessage)) {
            latestAt--;
        }
        List<Map<String, String>> recent = new ArrayList<>();
        for (int i = latestAt - 1; i >= 0 && recent.size() < RECENT_MESSAGES; i--) {
            Message m = working.get(i);
            String text = m.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (m instanceof AssistantMessage) {
                recent.add(Map.of("role", "assistant", "text", clip(text)));
            } else if (m instanceof UserMessage && !EXPERT_TAG.matcher(text).find() && !isSystemTail(text)) {
                recent.add(Map.of("role", "user", "text", clip(stripTurnMarks(text))));
            }
        }
        Collections.reverse(recent);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("latest_question", latestAt < 0 ? "" : clip(stripTurnMarks(working.get(latestAt).getText())));
        state.put("recent_conversation", recent);
        return state;
    }

    /** 进汇总前垫的系统收尾恒以输出语言指令结尾（见 ChatTurnRunner.summaryTail），落在历史里也认得出 */
    private boolean isSystemTail(String text) {
        for (AgentLang lang : AgentLang.values()) {
            if (text.endsWith(prompts.get(lang, "chat.outputLanguage"))) {
                return true;
            }
        }
        return false;
    }

    /** 剥掉轮起始标记：时间行 + 问题前缀（拼法见 ChatTurnStreamer.Turn.run）。标记按写入时的语言认 */
    private String stripTurnMarks(String text) {
        for (AgentLang lang : AgentLang.values()) {
            if (!text.startsWith(prompts.get(lang, "chat.turn.timePrefix"))) {
                continue;
            }
            int nl = text.indexOf('\n');
            String rest = nl < 0 ? "" : text.substring(nl + 1);
            String prefix = prompts.get(lang, "chat.turn.questionPrefix");
            return rest.startsWith(prefix) ? rest.substring(prefix.length()) : rest;
        }
        return text;
    }

    private static String clip(String text) {
        String t = text.strip();
        return t.length() > TEXT_LIMIT ? t.substring(0, TEXT_LIMIT) : t;
    }
}
