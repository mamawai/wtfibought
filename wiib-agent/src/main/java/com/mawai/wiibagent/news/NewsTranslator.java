package com.mawai.wiibagent.news;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.runtime.AiAgentRuntime.NamedModel;
import com.mawai.wiibquant.mapper.NewsEventMapper.Untranslated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 快讯批量译文：一批快讯一次轻模型调用，产出英文标题与正文。
 * <p>
 * 译文目标语言恒定英文（源是 BlockBeats 中文快讯），与用户语言无关——这是平台后台任务，没有"当前用户"。
 * <p>
 * <b>缺译文只留空，绝不拿原文冒充</b>：写回原文的话，取用侧就再也分不清"没译成"和"本来就这样"。
 * <p>
 * <b>失败是批级的</b>：每批按顺序试模型，主位抛错才换下一个；全挂只丢这一批的译文，
 * 行还在库里待译，下轮再试。其他批照常回填。
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

    private final PromptCatalog prompts;

    /**
     * 一条快讯的译文。
     *
     * @param titleEn   标题英文译文；null=没译成，取用侧回落中文原文
     * @param contentEn 正文英文译文；null 同上
     * @param model     实际译出这批的模型名，落 translated_model
     */
    public record Translated(String titleEn, String contentEn, String model) {
    }

    /**
     * @param models 按顺序试的模型，主位在前
     * @return 快讯id → 译文。<b>只含译成功的那几批</b>——map 里没有的条目留着待译
     */
    public Map<Long, Translated> translate(List<NamedModel> models, List<Untranslated> rows) {
        Map<Long, Translated> result = new HashMap<>();
        for (int from = 0; from < rows.size(); from += CHUNK) {
            result.putAll(translateChunk(models, rows.subList(from, Math.min(from + CHUNK, rows.size()))));
        }
        return result;
    }

    /** 主位成功就不碰后面的；全挂返回空 */
    private Map<Long, Translated> translateChunk(List<NamedModel> models, List<Untranslated> chunk) {
        StringBuilder list = new StringBuilder();
        Set<Long> clipped = new HashSet<>();
        for (Untranslated row : chunk) {
            String content = row.getContent();
            if (content.length() > CONTENT_CLIP) {
                content = content.substring(0, CONTENT_CLIP);
                clipped.add(row.getSourceId());
            }
            list.append(prompts.get(PROMPT_LANG, "news.flashLine", Map.of(
                    "id", row.getSourceId(), "title", row.getTitle(), "content", content))).append('\n');
        }
        Prompt prompt = new Prompt(prompts.get(PROMPT_LANG, "news.translate", Map.of("flashes", list)));

        for (NamedModel model : models) {
            Map<Long, Translated> parsed;
            try {
                parsed = parse(model.chatModel().call(prompt).getResult().getOutput().getText(), model.name());
            } catch (Exception e) {
                log.warn("[NewsTranslate] {} 译本批 {} 条失败: {}", model.name(), chunk.size(), e.toString());
                continue;
            }
            // 整批解析成功＝这批过过模型了：漏答的按没译成算，不留到下轮白烧一次
            Translated empty = new Translated(null, null, model.name());
            Map<Long, Translated> out = new HashMap<>();
            for (Untranslated row : chunk) {
                Translated t = parsed.getOrDefault(row.getSourceId(), empty);
                out.put(row.getSourceId(), clipped.contains(row.getSourceId())
                        ? new Translated(t.titleEn(), null, t.model()) : t);
            }
            return out;
        }
        return Map.of();
    }

    /** 宽进严出：JSON 前后可能裹着废话，截取首尾中括号；空白译文当没译。 */
    static Map<Long, Translated> parse(String output, String model) {
        int from = output.indexOf('[');
        int to = output.lastIndexOf(']');
        if (from < 0 || to <= from) {
            throw new IllegalStateException("翻译输出不含 JSON 数组: "
                    + output.substring(0, Math.min(200, output.length())));
        }
        ArrayNode arr = MAPPER.readValue(output.substring(from, to + 1), ArrayNode.class);
        Map<Long, Translated> result = new HashMap<>();
        for (JsonNode item : arr) {
            if (!item.has("id")) {
                continue;
            }
            result.put(item.path("id").asLong(0), new Translated(
                    translation(item.path("title_en").asString(null)),
                    translation(item.path("content_en").asString(null)), model));
        }
        return result;
    }

    /** 空白译文＝没译成，一律归 null；绝不在这里回填中文原文 */
    private static String translation(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
