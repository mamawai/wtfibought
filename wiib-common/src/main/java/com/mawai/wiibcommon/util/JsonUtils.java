package com.mawai.wiibcommon.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

public class JsonUtils {

    /**
     * 业务 JSON 共用一个：缓存、落库、SSE 帧、LLM 请求体、上游回包解析。HTTP 接口出站走 Spring 自己那个，不是它。
     * <ul>
     *   <li>null 不输出：对象字段、Map 值、树节点都一样，没值的键直接缺席</li>
     *   <li>读成树/Map 时小数给 BigDecimal，原样转出去不丢位（默认是 Double）</li>
     *   <li>null 读进 int/long/boolean 给默认值，不抛</li>
     *   <li>读的时候放行单引号、尾逗号、注释、字符串里没转义的换行（LLM 吐的 JSON）</li>
     * </ul>
     * 树节点转字符串必须走 {@code MAPPER.writeValueAsString}，{@code node.toString()} 不认上面这些配置。
     */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl
                    .withValueInclusion(JsonInclude.Include.NON_NULL)
                    .withContentInclusion(JsonInclude.Include.NON_NULL))
            .disable(JsonNodeFeature.WRITE_NULL_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES,
                    JsonReadFeature.ALLOW_TRAILING_COMMA,
                    JsonReadFeature.ALLOW_JAVA_COMMENTS,
                    JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    private JsonUtils() {}

    /**
     * 从文本中提取第一个JSON对象。
     * 自动移除 {@code <think>...</think>} 等模型思考标签及 markdown 代码块包裹。
     */
    public static String extractJson(String text) {
        if (text == null) return "{}";
        // 移除 <think>...</think> 标签（含换行）
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        // 移除 markdown 代码块包裹 ```json ... ```
        cleaned = cleaned.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```$", "");
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1).replace("\r", "").replace("\n", " ");
        }
        return "{}";
    }
}
