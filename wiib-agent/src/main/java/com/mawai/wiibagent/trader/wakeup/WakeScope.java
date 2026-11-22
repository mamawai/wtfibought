package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTrader;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一轮唤醒管哪些币。整盘轮管全部可交易币；单币轮只管把它叫醒的那一个。
 *
 * @param tradable 这个 trader 的全部可交易币
 * @param symbol   单币轮管的那个币；整盘轮为 null
 */
public record WakeScope(Set<String> tradable, String symbol) {

    /** 整盘轮 */
    public static WakeScope book(AiTrader trader) {
        return new WakeScope(tradableOf(trader), null);
    }

    /** 一个币的事该起哪种轮：这个币可交易就是它的单币轮；不归某一个币、或者这个币已经不在可交易币里，按整盘 */
    public static WakeScope of(AiTrader trader, String symbol) {
        Set<String> tradable = tradableOf(trader);
        return new WakeScope(tradable, symbol != null && tradable.contains(symbol) ? symbol : null);
    }

    /** trader 配的可交易币 */
    public static Set<String> tradableOf(AiTrader trader) {
        return Arrays.stream(trader.getSymbols().split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean single() {
        return symbol != null;
    }

    /** 这一轮管的币 */
    public Set<String> symbols() {
        return single() ? Set.of(symbol) : tradable;
    }
}
