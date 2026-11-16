package com.mawai.wiibagent.trader.trade;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/**
 * 动作记录（actions_json 里的一条）的状态词汇和读法。
 * 写的一侧是交易工具、预案执行和唤醒回路的合并，读的一侧是配对、复盘、对话和现场轨迹，两边都用这里的常量。
 * <p>
 * 整笔被护栏拒掉的动作没有 status，拒因放在 {@code rejected} 字段里；改之前落库的旧行也可能没有 status，那些按做成读。
 * 反手记为一次调用，平仓和反向开仓各是它 result 里的一条腿。
 */
public final class TradeActions {

    /** 做成了 */
    public static final String OK = "ok";
    /** sim 明确拒了或调用出错，没做成 */
    public static final String ERROR = "error";
    /** 结果未知：单可能已经成交，要对账 */
    public static final String UNKNOWN = "unknown";
    /** 只做成一部分：分档有的成了有的没成、反手只平没开、撤单只撤掉几张 */
    public static final String PARTIAL = "partial";
    /** 被拒：分档里的一档、反手的开仓腿、现场轨迹里的一条回执 */
    public static final String REJECTED = "rejected";
    /** 没执行：模型要调但调用次数用完或参数没对上，或者排在后面没轮到 */
    public static final String NOT_ATTEMPTED = "not_attempted";
    /** 反手的平仓腿：要平的仓位已经被强平 */
    public static final String LIQUIDATED = "liquidated";
    /** 现场轨迹里的一条回执：工具这次没取到数据 */
    public static final String UNAVAILABLE = "unavailable";

    /** 回执文本会带状态前缀的那几种；做成了的不带 */
    private static final List<String> PREFIXED = List.of(REJECTED, ERROR, UNKNOWN, PARTIAL);

    private TradeActions() {
    }

    /** 回给模型的文本开头的状态前缀，如 {@code "PARTIAL: "} */
    public static String replyPrefix(String status) {
        return status.toUpperCase(Locale.ROOT) + ": ";
    }

    /** 从回给模型的文本读回状态：认开头的前缀，没有前缀就是做成了 */
    public static String replyStatus(String reply) {
        for (String status : PREFIXED) {
            if (reply.startsWith(status.toUpperCase(Locale.ROOT) + ":")) {
                return status;
            }
        }
        return OK;
    }

    public static List<JsonNode> legs(JsonNode action) {
        if ("reverse_position".equals(action.path("tool").asString(null)) && action.has("result")) {
            return List.of(action.get("result").get("close"), action.get("result").get("open"));
        }
        return List.of(action);
    }

    /** 这个动作开没开成仓：整笔成了，或者分档里有哪一档成交了、挂上了 */
    public static boolean opened(JsonNode action) {
        return legs(action).stream().anyMatch(a -> "open_position".equals(a.path("tool").asString(null))
                && (OK.equals(a.path("status").asString(null)) || anyEntryPlaced(a.path("result").path("entries"))));
    }

    /**
     * 给对话看的一条动作名：做成的只有工具名，没做成的带上状态，如 {@code open_position(rejected)}。
     * 不带的话，被拒的、没执行的调用看起来和做成的一样。
     */
    public static String label(JsonNode action) {
        String tool = action.path("tool").asString(null);
        String status = action.has("rejected") ? REJECTED : action.path("status").asString(OK);
        return OK.equals(status) ? tool : tool + "(" + status + ")";
    }

    private static boolean anyEntryPlaced(JsonNode entries) {
        for (JsonNode e : entries) {
            String status = e.path("status").asString(null);
            if ("filled".equals(status) || "pending".equals(status)) {
                return true;
            }
        }
        return false;
    }
}
