package com.mawai.wiibagent.prediction;

import com.mawai.wiibquant.external.sim.SimPredictionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 预测员的 sim 账户：只有游戏钱包的机器人账户，一局一个——R1 是 jev-prediction，之后 jev-prediction-r{n}，
 * 新号注资 {@link #INITIAL_GAME_BALANCE}。首次用到时向 sim 幂等建号并记住 userId，sim 没起来就下次再试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevPredictionAccount {

    public static final String USERNAME = "jev-prediction";
    public static final BigDecimal INITIAL_GAME_BALANCE = new BigDecimal("100");

    private final SimPredictionClient client;

    private final Map<Integer, Long> userIds = new ConcurrentHashMap<>();

    /** 这一局账户的 userId；sim 不可达抛异常，调用方按"本次跳过"处理 */
    public long userId(int runNo) {
        return userIds.computeIfAbsent(runNo, n -> {
            Long id = client.ensureAccount(username(n), INITIAL_GAME_BALANCE);
            log.info("[JevPred] 账户就绪 R{} username={} userId={}", n, username(n), id);
            return id;
        });
    }

    static String username(int runNo) {
        return runNo == 1 ? USERNAME : USERNAME + "-r" + runNo;
    }
}
