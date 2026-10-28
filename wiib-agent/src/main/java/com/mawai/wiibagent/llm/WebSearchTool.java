package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 联网搜索包成的本地工具 web_search，给不收"服务端搜索 + 函数工具"同一请求的上游用（Antigravity 来源的 Gemini）。
 * 模型调它时，用同一个模型单独发一次只带服务端搜索、不带函数工具的请求，把据搜索结果写的答复和来源交回给模型。
 * 那次请求走阻塞路径，来源从 generation metadata 上取（见 {@link SseChatModel} 的帧合并）。
 * <p>
 * 挂了它就是"调用方授权"那一半，只给 chat 主 agent 与 researcher worker 挂（见 ChatAgentFactory）；
 * 那次请求里的服务端搜索还要过端点那道闸（{@link SseChatModel#WEB_SEARCH_KEY}）。
 */
public class WebSearchTool {

    public static final String NAME = "web_search";

    /** 那次搜索请求的系统提示：只报搜到的，不加自己的判断 */
    private static final String INSTRUCTION = "Search the web for the request below and report what the search results say: "
            + "the relevant facts with their dates, figures and who reported them. "
            + "Report only what the results contain; do not add analysis, opinions or advice of your own.";

    /**
     * 一次搜索的结果。
     *
     * @param searched 上游真搜了（报了搜索词或命中的站点）；没搜就是模型自己答的，不能当搜索结果
     * @param answer   据搜索结果写的答复
     * @param sources  来源，按 url 去重
     */
    public record Result(boolean searched, String answer, List<SearchEvent.Source> sources) {
    }

    private final LlmClient searcher;

    /** @param model 挂这个工具的 agent 自己的模型：带账本的话，搜索这次也记在它账上 */
    public WebSearchTool(ChatModel model) {
        this.searcher = LlmClient.builder().model(model).systemPrompt(INSTRUCTION).webSearch(true).build();
    }

    // description 只是兜底：发给模型的那份按语言取自词表 tool.web_search（见 LocalizedToolCallbacks）
    @Tool(name = NAME, description = """
            Search the web for the query. Returns an answer written from the search results (answer) and the
            sources it drew on (sources: title and url). Returns available=false when the search found nothing.""")
    public String webSearch(@ToolParam(description = "What to search for: a search query or a question") String query) {
        Result result = search(query);
        ObjectNode out = MAPPER.createObjectNode();
        if (result.searched()) {
            out.put("answer", result.answer());
            out.set("sources", SearchEvent.Source.toJson(result.sources()));
        } else {
            out.put("available", false).put("reason", "no web results");
        }
        return MAPPER.writeValueAsString(out);
    }

    /** 单独发一次只带服务端搜索的请求。端点探测（LlmEndpointService）也走这里：测的就是生产的样子 */
    public Result search(String query) {
        ChatResponse response = searcher.execute(List.of(new UserMessage(query)));
        List<SearchEvent> events = response.getResult().getMetadata().getOrDefault(SearchEvent.KEY, List.of());
        Map<String, SearchEvent.Source> sources = new LinkedHashMap<>();
        for (SearchEvent event : events) {
            for (SearchEvent.Source source : event.sources()) {
                sources.putIfAbsent(source.url(), source);
            }
        }
        return new Result(!events.isEmpty(), response.getResult().getOutput().getText(),
                List.copyOf(sources.values()));
    }

    /**
     * 回执里的来源（过程轨与答案底部用）；没搜成返回 null：工具失败、上游没搜、被保险丝或闸门跳过的回执里都没有 sources。
     * 工具出的 JSON 是一行，保险丝的预算提示贴在换行后面（见 ModelCallLimiter），只读第一行
     */
    public static List<SearchEvent.Source> sourcesOf(String responseData) {
        int cut = responseData.indexOf('\n');
        try {
            JsonNode sources = MAPPER.readTree(cut < 0 ? responseData : responseData.substring(0, cut)).get("sources");
            return sources == null ? null : SearchEvent.Source.fromJson(sources);
        } catch (JacksonException e) {
            return null;
        }
    }
}
