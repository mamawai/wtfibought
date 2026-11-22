package com.mawai.wiibagent.trader.trade;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 每个 trader 一个账户闸，同一个 trader 的几轮和预案执行拿到的是同一个。单实例部署，进程内 Map 即可 */
@Component
public class AccountGates {

    private final Map<Long, AccountGate> gates = new ConcurrentHashMap<>();

    public AccountGate of(long traderId) {
        return gates.computeIfAbsent(traderId, _ -> new AccountGate());
    }
}
