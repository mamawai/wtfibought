package com.mawai.wiibagent.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 服务端搜索的过程事件，三个自研协议共用一个形状。
 * 模型层把它作为<b>空文本帧</b>发出，挂在 ChatResponseMetadata 的 {@link #KEY} 上（值是本记录的 JSON 串）；
 * ChatTurnRunner 逐帧取出交给 SSE 出口，前端据此画"正在搜索 / 搜索了 N 个网站"，来源攒到答案底部。
 *
 * @param phase   {@link #SEARCHING}=开始搜（query 可能还没有）；{@link #SEARCHED}=搜完（sources 是命中的站点）；
 *                {@link #CITED}=正文引用了某个来源（只并入本轮来源，不进过程轨）
 * @param query   搜索词，可空
 * @param sources 站点列表，可空表示没有
 */
public record SearchEvent(String phase, String query, List<Source> sources) {

    public static final String KEY = "wiib_search";
    public static final String SEARCHING = "searching";
    public static final String SEARCHED = "searched";
    public static final String CITED = "cited";

    public record Source(String url, String title) {

        /** 来源列表 ⇄ [{url,title}]：SSE 的 search/done 事件、历史接口、库里的 sources 列都是这个形状 */
        public static ArrayNode toJson(List<Source> sources) {
            ArrayNode list = MAPPER.createArrayNode();
            for (Source s : sources) {
                list.add(MAPPER.createObjectNode().put("url", s.url()).put("title", s.title()));
            }
            return list;
        }

        public static List<Source> fromJson(JsonNode list) {
            List<Source> sources = new ArrayList<>();
            if (list != null) {
                for (JsonNode s : list) {
                    sources.add(new Source(s.path("url").asString(null), s.path("title").asString(null)));
                }
            }
            return sources;
        }
    }

    public static SearchEvent searching(String query) {
        return new SearchEvent(SEARCHING, query, List.of());
    }

    public static SearchEvent searched(String query, List<Source> sources) {
        return new SearchEvent(SEARCHED, query, sources);
    }

    public static SearchEvent cited(List<Source> sources) {
        return new SearchEvent(CITED, null, sources);
    }

    /** 与 SSE search 事件同形 */
    public ObjectNode toJsonObject() {
        return MAPPER.createObjectNode().put("phase", phase).put("query", query)
                .set("sources", Source.toJson(sources));
    }

    public String toJson() {
        return MAPPER.writeValueAsString(toJsonObject());
    }

    public static SearchEvent parse(String json) {
        JsonNode o = MAPPER.readTree(json);
        return new SearchEvent(o.path("phase").asString(null), o.path("query").asString(null), Source.fromJson(o.get("sources")));
    }
}
