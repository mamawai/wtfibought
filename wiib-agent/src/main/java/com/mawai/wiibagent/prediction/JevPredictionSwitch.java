package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.cache.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 预测员总开关：/admin 页面拨，存 Redis 不过期。没这个 key 算关——没人点过开就不跑，Redis 丢数据也是回到关。
 * 平台 JEV_API_KEY 没配时开关开着也不跑。
 */
@Component
@RequiredArgsConstructor
public class JevPredictionSwitch {

    static final String KEY = "jev:prediction:enabled";

    private final CacheService cache;

    public boolean isOn() {
        return "1".equals(cache.get(KEY));
    }

    public void set(boolean on) {
        cache.set(KEY, on ? "1" : "0");
    }
}
