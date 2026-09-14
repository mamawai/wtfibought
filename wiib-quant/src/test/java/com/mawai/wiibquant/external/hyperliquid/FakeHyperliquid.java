package com.mawai.wiibquant.external.hyperliquid;

import com.mawai.wiibquant.whale.WhaleProperties;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 测试用：真客户端换假上游与假时钟（桶不够就直接把钟拨过去，不真睡）。
 * 注入点是包私有的，别的包的测试（WhalePoolTaskTest 等）从这里拿。
 */
public final class FakeHyperliquid {

    private FakeHyperliquid() {
    }

    /** post：请求体 JSON → 回包正文；get：URL → 正文流 */
    public static HyperliquidClient client(WhaleProperties props, Function<String, String> post,
                                           Function<String, InputStream> get) {
        HyperliquidClient c = new HyperliquidClient(props);
        AtomicLong clock = new AtomicLong();
        c.nowMs = clock::get;
        c.sleeper = clock::addAndGet;
        c.post = post;
        c.get = get;
        return c;
    }
}
