package com.mawai.wiibagent.handover;

import com.mawai.wiibagent.learning.ReviewScheduler;
import com.mawai.wiibagent.trader.wakeup.PresetExecutor;
import com.mawai.wiibagent.trader.wakeup.TraderLiveHub;
import com.mawai.wiibagent.trader.wakeup.TraderPeer;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 对端实例和部署脚本打进来的交接接口（agent 自己的那几个；status / yield / takeover / resume 在 wiib-common）。
 * 只做解码和转调。走 InternalApiFilter 的 token 校验
 */
@RestController
@RequestMapping("/internal/handoff")
@RequiredArgsConstructor
public class HandoffPeerController {

    private final TraderScheduler scheduler;
    private final ReviewScheduler reviews;
    private final PresetExecutor presets;
    private final TraderLiveHub liveHub;
    private final ArrivalJson json;

    /** 旧实例：新实例转来的事，交给在跑的轮 */
    @PostMapping("/offer")
    public Map<String, String> offer(@RequestBody JsonNode body) {
        return Map.of("offer", scheduler.offerFromNew(body.path("traderId").asLong(), json.decode(body.get("arrival"))).name());
    }

    /** 旧实例：新实例转来的预案，还持有就执行并等它做完；做完再看能不能放手这个 trader */
    @PostMapping("/preset")
    public TraderPeer.PresetReply preset(@RequestBody JsonNode body) {
        long traderId = body.path("traderId").asLong();
        TraderPeer.PresetReply reply = presets.runForPeer(body.path("watchId").asLong(), body.path("price").asDecimal(),
                body.path("at").asLong());
        if (reply.held()) {
            scheduler.releaseIfIdle(traderId);
        }
        return reply;
    }

    /** 新实例：旧实例交还来的事 */
    @PostMapping("/handback")
    public void handback(@RequestBody JsonNode body) {
        scheduler.handedBack(body.path("traderId").asLong(), json.decode(body.get("arrival")), body.path("fresh").asBoolean());
    }

    /** 新实例：旧实例放手了一个 trader 的唤醒，或者一个复盘位 */
    @PostMapping("/released")
    public void released(@RequestBody JsonNode body) {
        long traderId = body.path("traderId").asLong();
        switch (TraderPeer.Held.valueOf(body.path("what").asString())) {
            case WAKE -> scheduler.releasedByOld(traderId);
            case REVIEW -> reviews.releasedByOld(traderId);
        }
    }

    /** 旧实例：切完网关后，脚本让它把不持有的 trader 的实时页连接完成掉，前端重连到新实例 */
    @PostMapping("/release-streams")
    public void releaseStreams() {
        liveHub.completeExcept(scheduler.heldHere());
    }
}
