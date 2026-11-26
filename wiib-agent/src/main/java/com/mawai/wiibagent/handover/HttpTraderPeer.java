package com.mawai.wiibagent.handover;

import com.mawai.wiibagent.trader.wakeup.Arrival;
import com.mawai.wiibagent.trader.wakeup.Offer;
import com.mawai.wiibagent.trader.wakeup.TraderPeer;
import com.mawai.wiibcommon.handover.PeerClient;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/** 走 HTTP 的对端：调另一个槽位的 /internal/handoff/offer、preset、handback、released（{@link HandoffPeerController}） */
@Slf4j
public class HttpTraderPeer implements TraderPeer {

    /** 通知放手失败后的重试间隔上限 */
    private static final long RETRY_MAX_MS = 30_000;
    /** 通知放手最多试多久；再久对端的探活也会判旧实例没了、全部放手 */
    private static final long RETRY_FOR_MS = 120_000;

    private final PeerClient client;
    private final ArrivalJson json;

    public HttpTraderPeer(PeerClient client, ArrivalJson json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public Offer offer(long traderId, Arrival arrival) {
        ObjectNode body = MAPPER.createObjectNode().put("traderId", traderId);
        body.set("arrival", json.encode(arrival));
        JsonNode reply = client.post("/internal/handoff/offer", body, JsonNode.class);
        return Offer.valueOf(reply.path("offer").asString());
    }

    @Override
    public PresetReply preset(long traderId, long watchId, BigDecimal price, long at) {
        ObjectNode body = MAPPER.createObjectNode().put("traderId", traderId).put("watchId", watchId)
                .put("price", price).put("at", at);
        JsonNode reply = client.postSlow("/internal/handoff/preset", body, JsonNode.class);
        return new PresetReply(reply.path("held").asBoolean(), reply.path("wake").asBoolean());
    }

    @Override
    public void handBack(long traderId, Arrival arrival, boolean fresh) {
        ObjectNode body = MAPPER.createObjectNode().put("traderId", traderId).put("fresh", fresh);
        body.set("arrival", json.encode(arrival));
        client.send("/internal/handoff/handback", body);
    }

    /** 失败就隔一会儿再发（1、2、4 秒……最多 30 秒一次），最多试 2 分钟 */
    @Override
    public void released(long traderId, Held what) {
        ObjectNode body = MAPPER.createObjectNode().put("traderId", traderId).put("what", what.name());
        long deadline = System.currentTimeMillis() + RETRY_FOR_MS;
        long wait = 1_000;
        while (true) {
            try {
                client.send("/internal/handoff/released", body);
                return;
            } catch (Exception e) {
                if (System.currentTimeMillis() + wait > deadline) {
                    log.error("[Handoff] 通知对端放手一直失败，不再试 traderId={} {} msg={}", traderId, what, e.toString());
                    return;
                }
                log.warn("[Handoff] 通知对端放手失败，{}ms 后重试 traderId={} {} msg={}", wait, traderId, what, e.toString());
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, RETRY_MAX_MS);
        }
    }
}
