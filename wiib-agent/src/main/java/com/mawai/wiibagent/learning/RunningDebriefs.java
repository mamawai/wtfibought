package com.mawai.wiibagent.learning;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个交易员正在做单笔复盘的那个仓位：复盘位一次只跑一个，一个交易员至多一笔。
 * 单笔复盘开跑记上、跑完清掉，交易卡片按它显示"复盘中"
 */
@Component
public class RunningDebriefs {

    private final Map<Long, Long> positionByTrader = new ConcurrentHashMap<>();

    void start(long traderId, long positionId) {
        positionByTrader.put(traderId, positionId);
    }

    void end(long traderId) {
        positionByTrader.remove(traderId);
    }

    /** 这个交易员正在复盘哪个仓位；没在复盘为 null */
    public Long positionOf(long traderId) {
        return positionByTrader.get(traderId);
    }
}
