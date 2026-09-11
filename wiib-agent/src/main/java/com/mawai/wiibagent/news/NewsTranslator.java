package com.mawai.wiibagent.news;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快讯批量译文：一批快讯一次轻模型调用，产出英文标题与正文。
 * <p>
 * 译文目标语言恒定英文（源是 BlockBeats 中文快讯），与用户语言无关——这是平台后台任务，没有"当前用户"。
 * <p>
 * <b>缺译文只留空，绝不拿原文冒充</b>：写回原文的话，取用侧就再也分不清"没译成"和"本来就这样"。
 * <p>
 * <b>失败是批级的</b>：某一批挂了只丢那一批（这几条本轮不入库，下轮还在拉取窗口里，重试自愈），
 * 已经译好的其他批照常落库——译文出参贵，不为一批的失败把整轮成果扔掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsTranslator {

    /**
     * 提示词用哪门语言：与"译成什么语言"是两件事。译文目标固定英文（两份 news.yml 里都写死），
     * 这里选的只是指令本身的写法——整篇英文是"译文写英文"最稳的信号，喂进去的素材是中文不受影响。
     */
    private static final AgentLang PROMPT_LANG = AgentLang.EN;

    /**
     * 单条正文进提示词的长度上限。这道线同时也是译文的覆盖范围：超过它的正文只送进去了头部，
     * 那条就不留正文译文（见 {@link #translateChunk}）——半截译文比中文原文更糟。
     */
    private static final int CONTENT_CLIP = 800;

    /**
     * 一次调用处理几条。每条要吐一份标题+正文译文，20 条一次逼近一万 token，撞上轻模型 4096/8192 的
     * 默认出参上限就是整串 JSON 被截断、整轮全废。5 条：典型出参约 800 token，最坏约 3000 token，留得住余量。
     */
    private static final int CHUNK = 5;

    /** 模型在本批里漏答的条目：这批已经过过模型，缺席按"没译成"落库 */
    private static final Translated EMPTY = new Translated(null, null);

    private final PromptCatalog prompts;

    /**
     * 一条快讯的译文。
     *
     * @param titleEn   标题英文译文；null=没译成，取用侧回落中文原文
     * @param contentEn 正文英文译文；null 同上
     */
    public record Translated(String titleEn, String contentEn) {
    }

    /**
     * @return 快讯id → 译文。<b>只含所在批调用成功的那些</b>——map 里没有的条目本轮别入库，
     *         入了就等于永远失去译文机会（去重键挡住重入）
     */
    public Map<Long, Translated> translate(ChatModel model, List<NewsFlash> flashes) {
        Map<Long, Translated> result = new HashMap<>();
        for (int from = 0; from < flashes.size(); from += CHUNK) {
            List<NewsFlash> chunk = flashes.subList(from, Math.min(from + CHUNK, flashes.size()));
            try {
                result.putAll(translateChunk(model, chunk));
            } catch (Exception e) {
                log.warn("[NewsTranslate] 本批 {} 条翻译失败，本轮跳过这几条: {}", chunk.size(), e.toString());
            }
        }
        return result;
    }

    private Map<Long, Translated> translateChunk(ChatModel model, List<NewsFlash> chunk) {
        StringBuilder list = new StringBuilder();
        Set<Long> clipped = new HashSet<>();
        for (NewsFlash f : chunk) {
            String content = f.plainContent();
            if (content.length() > CONTENT_CLIP) {
                content = content.substring(0, CONTENT_CLIP);
                clipped.add(f.id());
            }
            list.append(prompts.get(PROMPT_LANG, "news.flashLine", Map.of(
                    "id", f.id(), "title", f.title(), "content", content))).append('\n');
        }
        String output = model.call(new Prompt(prompts.get(PROMPT_LANG, "news.translate", Map.of("flashes", list))))
                .getResult().getOutput().getText();
        Map<Long, Translated> parsed = parse(output);

        Map<Long, Translated> out = new HashMap<>();
        for (NewsFlash f : chunk) {
            Translated t = parsed.getOrDefault(f.id(), EMPTY);
            out.put(f.id(), clipped.contains(f.id()) ? new Translated(t.titleEn(), null) : t);
        }
        return out;
    }

    /** 宽进严出：JSON 前后可能裹着废话，截取首尾中括号；空白译文当没译。 */
    static Map<Long, Translated> parse(String output) {
        int from = output.indexOf('[');
        int to = output.lastIndexOf(']');
        if (from < 0 || to <= from) {
            throw new IllegalStateException("翻译输出不含 JSON 数组: "
                    + output.substring(0, Math.min(200, output.length())));
        }
        JSONArray arr = JSON.parseArray(output.substring(from, to + 1));
        Map<Long, Translated> result = new HashMap<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (item == null || !item.containsKey("id")) {
                continue;
            }
            result.put(item.getLongValue("id"), new Translated(
                    translation(item.getString("title_en")), translation(item.getString("content_en"))));
        }
        return result;
    }

    /** 空白译文＝没译成，一律归 null；绝不在这里回填中文原文 */
    private static String translation(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
