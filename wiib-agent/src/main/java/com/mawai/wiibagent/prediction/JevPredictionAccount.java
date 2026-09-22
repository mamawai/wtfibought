package com.mawai.wiibagent.prediction;

import com.mawai.wiibquant.external.sim.SimPredictionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 预测员的 sim 账户：只有游戏钱包的机器人账户，全站一个。首次用到时向 sim 幂等建号并记住 userId，
 * sim 没起来就下次再试，不挡 agent 启动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevPredictionAccount {

    public static final String USERNAME = "jev-prediction";
    public static final BigDecimal INITIAL_GAME_BALANCE = new BigDecimal("100");

    private final SimPredictionClient client;

    private volatile Long userId;

    /** 建号成功返回 userId；sim 不可达抛异常，调用方按"本次跳过"处理 */
    public long userId() {
        Long id = userId;
        if (id != null) {
            return id;
        }
        synchronized (this) {
            if (userId == null) {
                userId = client.ensureAccount(USERNAME, INITIAL_GAME_BALANCE);
                log.info("[JevPred] 账户就绪 username={} userId={}", USERNAME, userId);
            }
            return userId;
        }
    }
}
